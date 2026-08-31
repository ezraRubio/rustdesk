use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::JNIEnv;
use log::{Level, LevelFilter, Log, Metadata, Record};
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
}

static LOGGING_INIT: Once = Once::new();
static LOG_BRIDGE_CACHE_INIT: Once = Once::new();
static LOG_BRIDGE_CACHE: RwLock<Option<LogBridgeCache>> = RwLock::new(None);

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

    let global = env.new_global_ref(class_obj).map_err(|_| {
        clear_exception(env);
    })?;

    LOG_BRIDGE_CACHE
        .write()
        .map_err(|_| ())?
        .replace(LogBridgeCache { class: global });
    Ok(())
}

fn call_log_bridge(priority: AndroidLogPriority, message: &str) -> Result<(), ()> {
    super::ffi::with_java_vm(|jvm| {
        let mut env = jvm.attach_current_thread().map_err(|_| ())?;
        clear_exception(&mut env);

        let cache = LOG_BRIDGE_CACHE.read().map_err(|_| ())?;
        let cache = cache.as_ref().ok_or(())?;

        let msg = env.new_string(message).map_err(|_| ())?;
        env.call_static_method(
            &cache.class,
            "log",
            "(ILjava/lang/String;)V",
            &[JValue::Int(priority as i32), JValue::Object(&msg)],
        )
        .map_err(|_| {
            clear_exception(&mut env);
        })?;
        Ok(())
    })
    .ok_or(())?
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
        } else if LOG_BRIDGE_CACHE.read().is_ok_and(|guard| guard.is_some()) {
            let _ = log::set_boxed_logger(Box::new(JniLogger));
            log::set_max_level(LevelFilter::Info);
        } else {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(LevelFilter::Info)
                    .with_tag("rust"),
            );
        }
    });
}
