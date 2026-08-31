use hbb_common::libc;
use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::jmethodID;
use jni::JNIEnv;
use log::{Level, LevelFilter, Log, Metadata, Record};
use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::io::AsRawFd;
use std::path::PathBuf;
use std::sync::{Once, RwLock};

#[repr(i32)]
enum AndroidLogPriority {
    Debug = 3,
    Info = 4,
    Warn = 5,
    Error = 6,
}

impl From<Level> for AndroidLogPriority {
    fn from(level: Level) -> Self {
        match level {
            Level::Error => Self::Error,
            Level::Warn => Self::Warn,
            Level::Info => Self::Info,
            Level::Debug | Level::Trace => Self::Debug,
        }
    }
}

struct LogBridgeCache {
    class: GlobalRef,
    log_method: jmethodID,
}

static LOGGING_INIT: Once = Once::new();
static LOG_BRIDGE_CACHE_INIT: Once = Once::new();
static CRASH_FILE_INIT: Once = Once::new();
static LOG_BRIDGE_CACHE: RwLock<Option<LogBridgeCache>> = RwLock::new(None);
static CRASH_REPORT_PATH: RwLock<Option<PathBuf>> = RwLock::new(None);
static CRASH_REPORT_FD: RwLock<Option<i32>> = RwLock::new(None);

struct JniLogger;

impl Log for JniLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= Level::Info
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let priority = AndroidLogPriority::from(record.level());
        let message = format!("{}", record.args());
        let _ = call_log_bridge(priority, &message);
    }

    fn flush(&self) {}
}

fn clear_exception(env: &mut JNIEnv) {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

fn cache_log_bridge(env: &mut JNIEnv, ctx: &JObject, class_name: &str) -> Result<(), ()> {
    clear_exception(env);

    let loader = env
        .call_method(ctx, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])
        .map_err(|_| {
            clear_exception(env);
        })?
        .l()
        .map_err(|_| {
            clear_exception(env);
        })?;
    clear_exception(env);

    let name = env.new_string(class_name).map_err(|_| {
        clear_exception(env);
    })?;
    let class_obj = env
        .call_method(
            loader,
            "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&name)],
        )
        .map_err(|_| {
            clear_exception(env);
        })?
        .l()
        .map_err(|_| {
            clear_exception(env);
        })?;
    clear_exception(env);

    let class = JClass::from(class_obj);
    let log_method = env
        .get_static_method_id(class, "log", "(ILjava/lang/String;)V")
        .map_err(|_| {
            clear_exception(env);
        })?;
    let global = env.new_global_ref(class_obj).map_err(|_| {
        clear_exception(env);
    })?;

    LOG_BRIDGE_CACHE
        .write()
        .map_err(|_| ())?
        .replace(LogBridgeCache {
            class: global,
            log_method,
        });
    Ok(())
}

fn call_log_bridge(priority: AndroidLogPriority, message: &str) -> Result<(), ()> {
    super::ffi::with_java_vm(|jvm| {
        let mut env = jvm.attach_current_thread().map_err(|_| ())?;
        clear_exception(&mut env);

        let cache = LOG_BRIDGE_CACHE.read().map_err(|_| ())?;
        let cache = cache.as_ref().ok_or(())?;

        let class = JClass::from(cache.class.as_obj());
        let msg = env.new_string(message).map_err(|_| ())?;

        let result = unsafe {
            env.call_static_method_unchecked(
                class,
                cache.log_method,
                "(ILjava/lang/String;)V",
                &[JValue::Int(priority as i32), JValue::Object(&msg)],
            )
        };
        if result.is_err() {
            clear_exception(&mut env);
            return Err(());
        }
        Ok(())
    })
    .ok_or(())?
}

fn write_crash_report_sync(report: &str) {
    if let Ok(fd_guard) = CRASH_REPORT_FD.read() {
        if let Some(fd) = *fd_guard {
            let bytes = report.as_bytes();
            let _ = unsafe {
                libc::write(fd, bytes.as_ptr() as *const libc::c_void, bytes.len())
            };
            return;
        }
    }
    if let Ok(path_guard) = CRASH_REPORT_PATH.read() {
        if let Some(path) = path_guard.as_ref() {
            let _ = OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .and_then(|mut file| file.write_all(report.as_bytes()));
        }
    }
}

fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let backtrace = std::backtrace::Backtrace::force_capture();
        let report = format!("Rust panic: {info}\n{backtrace}");
        write_crash_report_sync(&report);
    }));
}

fn prepare_crash_report_file(env: &mut JNIEnv, ctx: &JObject) {
    let Ok(files_dir) = env.call_method(ctx, "getFilesDir", "()Ljava/io/File;", &[]) else {
        return;
    };
    let files_dir = match files_dir.l() {
        Ok(obj) => obj,
        Err(_) => return,
    };
    let Ok(path_obj) = env.call_method(
        files_dir,
        "getAbsolutePath",
        "()Ljava/lang/String;",
        &[],
    ) else {
        return;
    };
    let Ok(path_jstr) = path_obj.l() else {
        return;
    };
    let path_jstring = JString::from(path_jstr);
    let Ok(path) = env.get_string(&path_jstring) else {
        return;
    };
    let path: String = path.into();
    let logs_dir = PathBuf::from(path).join("logs");
    let _ = std::fs::create_dir_all(&logs_dir);
    let crash_path = logs_dir.join("pending_native_crash.txt");
    if let Ok(file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&crash_path)
    {
        let fd = file.as_raw_fd();
        if let Ok(mut fd_guard) = CRASH_REPORT_FD.write() {
            *fd_guard = Some(fd);
        }
        if let Ok(mut path_guard) = CRASH_REPORT_PATH.write() {
            *path_guard = Some(crash_path);
        }
        std::mem::forget(file);
    }
}

pub fn init_android_logging_with_context(
    env: &mut JNIEnv,
    ctx: &JObject,
    dev_mode: bool,
    log_bridge_class: Option<&str>,
) {
    if !dev_mode {
        if let Some(class) = log_bridge_class.filter(|s| !s.is_empty()) {
            LOG_BRIDGE_CACHE_INIT.call_once(|| {
                let _ = cache_log_bridge(env, ctx, class);
            });
        }
    }

    LOGGING_INIT.call_once(|| {
        if dev_mode {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(LevelFilter::Debug)
                    .with_tag("rust"),
            );
        } else if LOG_BRIDGE_CACHE
            .read()
            .ok()
            .and_then(|guard| guard.as_ref())
            .is_some()
        {
            let _ = log::set_boxed_logger(Box::new(JniLogger));
            log::set_max_level(LevelFilter::Info);
        } else {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(LevelFilter::Info)
                    .with_tag("rust"),
            );
        }
        install_panic_hook();
    });

    CRASH_FILE_INIT.call_once(|| prepare_crash_report_file(env, ctx));
}
