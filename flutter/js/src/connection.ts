import Websock from "./websock";
import * as message from "./message.js";
import * as rendezvous from "./rendezvous.js";
import { loadVp9 } from "./codec";
import * as sha256 from "fast-sha256";
import * as globals from "./globals";
import { decompress, mapKey, sleep } from "./common";

const HOSTS = [
  "fortdesk.lan",
];
let HOST = localStorage.getItem("rendezvous-server") || HOSTS[0];

type MsgboxCallback = (type: string, title: string, text: string) => void;
type DrawCallback = (displayId: number, data: Uint8Array) => void;

export default class Connection {
  _msgs: any[];
  _ws: Websock | undefined;
  _interval: any;
  _id: string;
  _hash: message.Hash | undefined;
  _msgbox: MsgboxCallback;
  _draw: DrawCallback;
  _peerInfo: message.PeerInfo | undefined;
  _firstFrame: Boolean | undefined;
  _videoDecoder: any;
  _password: Uint8Array | undefined;
  _options: any;
  _videoTestSpeed: number[];
  _plaintextPassword: string | undefined;
  // this is for holding the last key pressed down for passing it as a up event
  _lastKeyDownEvent: number | undefined;

  constructor() {
    this._msgbox = globals.msgbox;
    this._draw = globals.draw;
    this._msgs = [];
    this._id = "";
    this._videoTestSpeed = [0, 0];
  }

  setPeerId(id: string) {
    this._id = id;
  }

  setPlaintextPassword(pw: string) {
    this._plaintextPassword = pw;
  }

  async start(id?: string, password?: string) {
    if (id) {
      this._id = id;
    }
    if (password) {
      this._plaintextPassword = password;
    }
    try {
      await this._start(this._id);
    } catch (e: any) {
      globals.setConnStatus(-1);
      this.msgbox(
        "error",
        "Connection Error",
        e.type == "close" ? "Reset by the peer" : String(e)
      );
    }
  }

  async _start(id: string) {
    if (!this._options) {
      this._options = globals.getPeers()[id] || {};
    }
    if (!this._password) {
      const p = this.getOption("password");
      if (p) {
        try {
          this._password = Uint8Array.from(JSON.parse("[" + p + "]"));
        } catch (e) {
          console.error(e);
        }
      }
    }
    this._interval = setInterval(() => {
      while (this._msgs.length) {
        this._ws?.sendMessage(this._msgs[0]);
        this._msgs.splice(0, 1);
      }
    }, 1);
    this.loadVideoDecoder();
    const uri = getDefaultUri();
    const ws = new Websock(uri, true);
    this._ws = ws;
    this._id = id;
    await ws.open();
    globals.setConnStatus(1);
    const conn_type = rendezvous.ConnType.DEFAULT_CONN;
    const nat_type = rendezvous.NatType.SYMMETRIC;
    const punch_hole_request = rendezvous.PunchHoleRequest.fromPartial({
      id,
      licence_key: localStorage.getItem("key") || undefined,
      conn_type,
      nat_type,
      token: localStorage.getItem("access_token") || undefined,
    });
    ws.sendRendezvous({ punch_hole_request });
    const msg = (await ws.next()) as rendezvous.RendezvousMessage;
    ws.close();
    const phr = msg.punch_hole_response;
    const rr = msg.relay_response;
    if (phr) {
      if (phr?.other_failure) {
        this.msgbox("error", "Error", phr?.other_failure);
        return;
      }
      if (phr.failure != rendezvous.PunchHoleResponse_Failure.UNRECOGNIZED) {
        let errorMsg = "";
        switch (phr?.failure) {
          case rendezvous.PunchHoleResponse_Failure.ID_NOT_EXIST:
            errorMsg = "ID does not exist";
            break;
          case rendezvous.PunchHoleResponse_Failure.OFFLINE:
            errorMsg = "Remote desktop is offline";
            break;
          case rendezvous.PunchHoleResponse_Failure.LICENSE_MISMATCH:
            errorMsg = "Key mismatch";
            break;
          case rendezvous.PunchHoleResponse_Failure.LICENSE_OVERUSE:
            errorMsg = "Key overuse";
            break;
          default:
            errorMsg = "Unknown error (code: " + phr.failure + ")";
        }
        this.msgbox("error", "Error", errorMsg);
        return;
      }
    } else if (rr) {
      if (!rr.version) {
        this.msgbox("error", "Error", "Remote version is low, not support web");
        return;
      }
      await this.connectRelay(rr);
    } else {
      console.error("No punch hole response or relay response in message!");
    }
  }

  async connectRelay(rr: rendezvous.RelayResponse) {
    const pk = rr.pk;
    let uri = rr.relay_server;
    
    const customHost = localStorage.getItem("custom-rendezvous-server");
    if (customHost) {
      uri = getDefaultUri(true);
    } else if (uri) {
      uri = getrUriFromRs(uri, true, 2);
    } else {
      uri = getDefaultUri(true);
    }
    
    const uuid = rr.uuid;
    const ws = new Websock(uri, false);
    await ws.open();
    this._ws = ws;
    const request_relay = rendezvous.RequestRelay.fromPartial({
      licence_key: localStorage.getItem("key") || undefined,
      uuid,
    });
    ws.sendRendezvous({ request_relay });
    const secure = (await this.secure(pk)) || false;
    globals.pushEvent("connection_ready", { secure, direct: false });
    await this.msgLoop();
  }

  async secure(pk: Uint8Array | undefined) {
    if (pk) {
      const RS_PK = "1+osBpZv1anmzl0VBLmVJXYvwDp3TN3nX+DS9YOcuXc=";
      try {
        pk = await globals.verify(pk, localStorage.getItem("key") || RS_PK);
        if (pk) {
          const idpk = message.IdPk.decode(pk);
          if (idpk.id == this._id) {
            pk = idpk.pk;
          }
        }
        if (pk?.length != 32) {
          pk = undefined;
        }
      } catch (e) {
        console.error(e);
        pk = undefined;
      }
      if (!pk)
        console.error(
          "Handshake failed: invalid public key from rendezvous server"
        );
    }
    if (!pk) {
      const public_key = message.PublicKey.fromPartial({});
      this._ws?.sendMessage({ public_key });
      return;
    }
    const msg = (await this._ws?.next()) as message.Message;
    let signedId: any = msg?.signed_id;
    if (!signedId) {
      console.error("Handshake failed: invalid message type");
      const public_key = message.PublicKey.fromPartial({});
      this._ws?.sendMessage({ public_key });
      return;
    }
    try {
      signedId = await globals.verify(signedId.id, Uint8Array.from(pk!));
    } catch (e) {
      console.error(e);
      console.error("pk mismatch, fall back to non-secure");
      const public_key = message.PublicKey.fromPartial({});
      this._ws?.sendMessage({ public_key });
      return;
    }
    const idpk = message.IdPk.decode(signedId);
    const id = idpk.id;
    const theirPk = idpk.pk;
    if (id != this._id!) {
      console.error("Handshake failed: sign failure");
      const public_key = message.PublicKey.fromPartial({});
      this._ws?.sendMessage({ public_key });
      return;
    }
    if (theirPk.length != 32) {
      console.error(
        "Handshake failed: invalid public box key length from peer"
      );
      const public_key = message.PublicKey.fromPartial({});
      this._ws?.sendMessage({ public_key });
      return;
    }
    const [mySk, asymmetric_value] = globals.genBoxKeyPair();
    const secret_key = globals.genSecretKey();
    const symmetric_value = globals.seal(secret_key, theirPk, mySk);
    const public_key = message.PublicKey.fromPartial({
      asymmetric_value,
      symmetric_value,
    });
    this._ws?.sendMessage({ public_key });
    this._ws?.setSecretKey(secret_key);
    return true;
  }

  async msgLoop() {
    console.log("Message loop started, waiting for messages...");
    let messageCount = 0;
    try {
      while (true) {
        const msg = (await this._ws?.next(0)) as message.Message;
        
        if (!msg) {
          break;
        }
        if (msg?.key_event) {
          console.log("key_event received: ", msg?.key_event);
        }
        
        messageCount++;
        
        if (msg?.hash) {
        this._hash = msg?.hash;
        if (this._plaintextPassword) {
          this.login(this._plaintextPassword);
        } else {
          if (!this._password)
            this.msgbox("input-password", "Password Required", "");
          this.login();
        }
      } else if (msg?.test_delay) {
        const test_delay = msg?.test_delay;
        if (!test_delay.from_client) {
          this._ws?.sendMessage({ test_delay });
        }
      } else if (msg?.login_response) {
        const r = msg?.login_response;
        if (r.error) {
          if (r.error == "Wrong Password") {
            this._password = undefined;
            this.msgbox(
              "re-input-password",
              r.error,
              "Do you want to enter again?"
            );
          } else {
            this.msgbox("error", "Login Error", r.error);
          }
        } else if (r.peer_info) {
          this.handlePeerInfo(r.peer_info);
        }
      } else if (msg?.video_frame) {
        this.handleVideoFrame(msg?.video_frame!);
      } else if (msg?.clipboard) {
        const cb = msg?.clipboard;
        if (cb.compress) {
          const c = await decompress(cb.content);
          if (!c) continue;
          cb.content = c;
        }
        try {
          globals.copyToClipboard(new TextDecoder().decode(cb.content));
        } catch (e) {
          console.error(e);
        }
      } else if (msg?.cursor_data) {
        const cd = msg?.cursor_data;
        const c = await decompress(cd.colors);
        if (!c) continue;
        cd.colors = c;
        globals.pushEvent("cursor_data", cd);
      } else if (msg?.cursor_id) {
        globals.pushEvent("cursor_id", { id: msg?.cursor_id });
      } else if (msg?.cursor_position) {
        globals.pushEvent("cursor_position", msg?.cursor_position);
      } else if (msg?.misc) {
        if (!this.handleMisc(msg?.misc)) break;
      } else if (msg?.audio_frame) {
        globals.playAudio(msg?.audio_frame.data);
      }
    }
    } catch (error) {
      console.error("Error type:", typeof error, "Value:", error);
      this.msgbox("error", "Connection Error", String(error));
    } finally {
      console.log(`Message loop ended after ${messageCount} messages`);
    }
  }

  msgbox(type_: string, title: string, text: string) {
    this._msgbox?.(type_, title, text);
  }

  draw(displayId: number, frame: any) {
    this._draw?.(displayId, frame);
    globals.draw(displayId, frame);
  }

  close() {
    this._msgs = [];
    clearInterval(this._interval);
    this._ws?.close();
    this._videoDecoder?.close();
  }

  refresh() {
    const misc = message.Misc.fromPartial({ refresh_video: true });
    this._ws?.sendMessage({ misc });
  }

  setMsgbox(callback: MsgboxCallback) {
    this._msgbox = callback;
  }

  setDraw(callback: DrawCallback) {
    this._draw = callback;
  }

  login(password: string | undefined = undefined) {
    if (password) {
      const salt = this._hash?.salt;
      let p = hash([password, salt!]);
      this._password = p;
      const challenge = this._hash?.challenge;
      p = hash([p, challenge!]);
      this.msgbox("connecting", "Connecting...", "Logging in...");
      this._sendLoginMessage(p);
    } else {
      let p = this._password;
      if (p) {
        const challenge = this._hash?.challenge;
        p = hash([p, challenge!]);
      }
      this._sendLoginMessage(p);
    }
  }

  async reconnect() {
    this.close();
    await this.start(this._id);
  }

  _sendLoginMessage(password: Uint8Array | undefined = undefined) {
    const login_request = message.LoginRequest.fromPartial({
      username: this._id!,
      my_id: "web",
      my_name: "web",
      password,
      option: this.getOptionMessage(),
      video_ack_required: true,
    });
    this._ws?.sendMessage({ login_request });
  }

  getOptionMessage(): message.OptionMessage | undefined {
    let n = 0;
    const msg = message.OptionMessage.fromPartial({});
    const q = this.getImageQualityEnum(this.getImageQuality(), true);
    const yes = message.OptionMessage_BoolOption.Yes;
    if (q != undefined) {
      msg.image_quality = q;
      n += 1;
    }
    if (this._options["show-remote-cursor"]) {
      msg.show_remote_cursor = yes;
      n += 1;
    }
    if (this._options["lock-after-session-end"]) {
      msg.lock_after_session_end = yes;
      n += 1;
    }
    if (this._options["privacy-mode"]) {
      msg.privacy_mode = yes;
      n += 1;
    }
    if (this._options["disable-audio"]) {
      msg.disable_audio = yes;
      n += 1;
    }
    if (this._options["disable-clipboard"]) {
      msg.disable_clipboard = yes;
      n += 1;
    }
    return n > 0 ? msg : undefined;
  }

  sendVideoReceived() {
    const misc = message.Misc.fromPartial({ video_received: true });
    this._ws?.sendMessage({ misc });
  }

  handleVideoFrame(vf: message.VideoFrame) {
    if (!this._firstFrame) {
      this.msgbox("", "", "");
      this._firstFrame = true;
    }
    if (vf.vp9s) {
      const dec = this._videoDecoder;
      if (!dec) {
        console.warn('[handleVideoFrame] VP9 decoder not ready yet, dropping frame');
        return;
      }
      var tm = new Date().getTime();
      var i = 0;
      const n = vf.vp9s?.frames.length;
      vf.vp9s.frames.forEach((f) => {
        dec.processFrame(f.data.slice(0).buffer, (ok: any) => {
          i++;
          if (i == n) this.sendVideoReceived();
          if (ok && dec.frameBuffer && n == i) {
            this.draw(vf.display as number, dec.frameBuffer);
            const now = new Date().getTime();
            var elapsed = now - tm;
            this._videoTestSpeed[1] += elapsed;
            this._videoTestSpeed[0] += 1;
            if (this._videoTestSpeed[0] >= 30) {
              this._videoTestSpeed = [0, 0];
            }
          }
        });
      });
    }
  }

  handlePeerInfo(pi: message.PeerInfo) {
    this._peerInfo = pi;
    if (pi.displays.length == 0) {
      this.msgbox("error", "Remote Error", "No Display");
      return;
    }
    this.msgbox("success", "Successful", "Connected, waiting for image...");
    globals.pushEvent("peer_info", pi);
    const p = this.shouldAutoLogin();
    if (p) this.inputOsPassword(p);
    const username = this.getOption("info")?.username;
    if (username && !pi.username) pi.username = username;
    this.setOption("info", pi);
    if (this.getRemember()) {
      if (this._password?.length) {
        const p = this._password.toString();
        if (p != this.getOption("password")) {
          this.setOption("password", p);
        }
      }
    } else {
      this.setOption("password", undefined);
    }
  }

  shouldAutoLogin(): string {
    const l = this.getOption("lock-after-session-end");
    const a = !!this.getOption("auto-login");
    const p = this.getOption("os-password");
    if (p && l && a) {
      return p;
    }
    return "";
  }

  handleMisc(misc: message.Misc) {
    if (misc.audio_format) {
      globals.initAudio(
        misc.audio_format.channels,
        misc.audio_format.sample_rate
      );
    } else if (misc.chat_message) {
      globals.pushEvent("chat", { text: misc.chat_message.text });
    } else if (misc.permission_info) {
      const p = misc.permission_info;
      let name;
      switch (p.permission) {
        case message.PermissionInfo_Permission.Keyboard:
          name = "keyboard";
          break;
        case message.PermissionInfo_Permission.Clipboard:
          name = "clipboard";
          break;
        case message.PermissionInfo_Permission.Audio:
          name = "audio";
          break;
        default:
          return true;
      }
      globals.pushEvent("permission", { [name]: p.enabled });
    } else if (misc.switch_display) {
      this.loadVideoDecoder();
      globals.pushEvent("switch_display", misc.switch_display);
    } else if (misc.close_reason) {
      this.msgbox("error", "Connection Error", misc.close_reason);
      this.close();
      return false;
    }
    return true;
  }

  getRemember(): Boolean {
    return this._options["remember"] || false;
  }

  setRemember(v: Boolean) {
    this.setOption("remember", v);
  }

  getOption(name: string): any {
    return this._options[name];
  }

  setOption(name: string, value: any) {
    if (value == undefined) {
      delete this._options[name];
    } else {
      this._options[name] = value;
    }
    this._options["tm"] = new Date().getTime();
    const peers = globals.getPeers();
    peers[this._id] = this._options;
    localStorage.setItem("peers", JSON.stringify(peers));
  }

  getToggleOption(name: string): Boolean {
    return this._options[name] || false;
  }

  getStatus(): string {
    return JSON.stringify({ status_num: _connStatus, video_conn_count: _videoConnCount });
  }

  inputKey(
    name: string,
    down: boolean,
    press: boolean,
    alt: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    command: Boolean
  ) {
    const key_event = mapKey(name, globals.isDesktop());
    if (down && key_event) {
      this._lastKeyDownEvent = key_event.chr;
    }
    if (!down && !key_event.chr) {
      key_event.chr = this._lastKeyDownEvent;
      this._lastKeyDownEvent = undefined;
    }
    if (alt && (name == "VK_MENU" || name == "RAlt")) {
      alt = false;
    }
    if (ctrl && (name == "VK_CONTROL" || name == "RControl")) {
      ctrl = false;
    }
    if (shift && (name == "VK_SHIFT" || name == "RShift")) {
      shift = false;
    }
    if (command && (name == "Meta" || name == "RWin")) {
      command = false;
    }
    key_event.down = down;
    key_event.press = press;
    key_event.modifiers = this.getMod(alt, ctrl, shift, command);
    this._ws?.sendMessage({ key_event });
  }

  ctrlAltDel() {
    const key_event = message.KeyEvent.fromPartial({ down: true });
    if (this._peerInfo?.platform == "Windows") {
      key_event.control_key = message.ControlKey.CtrlAltDel;
    } else {
      key_event.control_key = message.ControlKey.Delete;
      key_event.modifiers = this.getMod(true, true, false, false);
    }
    this._ws?.sendMessage({ key_event });
  }

  inputString(seq: string) {
    const key_event = message.KeyEvent.fromPartial({ seq });
    this._ws?.sendMessage({ key_event });
  }

  switchDisplay(display: number) {
    const switch_display = message.SwitchDisplay.fromPartial({ display });
    const misc = message.Misc.fromPartial({ switch_display });
    this._ws?.sendMessage({ misc });
  }

  async inputOsPassword(seq: string) {
    console.log("am i ever here?", seq);
    this.inputMouse();
    await sleep(50);
    this.inputMouse(0, 3, 3);
    await sleep(50);
    this.inputMouse(1 | (1 << 3));
    this.inputMouse(2 | (1 << 3));
    await sleep(1200);
    const key_event = message.KeyEvent.fromPartial({ press: true, seq });
    this._ws?.sendMessage({ key_event });
  }

  lockScreen() {
    const key_event = message.KeyEvent.fromPartial({
      down: true,
      control_key: message.ControlKey.LockScreen,
    });
    this._ws?.sendMessage({ key_event });
  }

  getMod(alt: Boolean, ctrl: Boolean, shift: Boolean, command: Boolean) {
    const mod: message.ControlKey[] = [];
    if (alt) mod.push(message.ControlKey.Alt);
    if (ctrl) mod.push(message.ControlKey.Control);
    if (shift) mod.push(message.ControlKey.Shift);
    if (command) mod.push(message.ControlKey.Meta);
    return mod;
  }

  inputMouse(
    mask: number = 0,
    x: number = 0,
    y: number = 0,
    alt: Boolean = false,
    ctrl: Boolean = false,
    shift: Boolean = false,
    command: Boolean = false
  ) {
    const mouse_event = message.MouseEvent.fromPartial({
      mask,
      x,
      y,
      modifiers: this.getMod(alt, ctrl, shift, command),
    });
    this._ws?.sendMessage({ mouse_event });
  }

  toggleOption(name: string) {
    const v = !this._options[name];
    const option = message.OptionMessage.fromPartial({});
    const v2 = v
      ? message.OptionMessage_BoolOption.Yes
      : message.OptionMessage_BoolOption.No;
    switch (name) {
      case "show-remote-cursor":
        option.show_remote_cursor = v2;
        break;
      case "disable-audio":
        option.disable_audio = v2;
        break;
      case "disable-clipboard":
        option.disable_clipboard = v2;
        break;
      case "lock-after-session-end":
        option.lock_after_session_end = v2;
        break;
      case "privacy-mode":
        option.privacy_mode = v2;
        break;
      case "block-input":
        option.block_input = message.OptionMessage_BoolOption.Yes;
        break;
      case "unblock-input":
        option.block_input = message.OptionMessage_BoolOption.No;
        break;
      default:
        return;
    }
    if (name.indexOf("block-input") < 0) this.setOption(name, v);
    const misc = message.Misc.fromPartial({ option });
    this._ws?.sendMessage({ misc });
  }

  togglePrivacyMode(value: string) {
    const option = message.OptionMessage.fromPartial({
      privacy_mode: value === "true"
        ? message.OptionMessage_BoolOption.Yes
        : message.OptionMessage_BoolOption.No,
    });
    const misc = message.Misc.fromPartial({ option });
    this._ws?.sendMessage({ misc });
  }

  getImageQuality() {
    return this.getOption("image-quality");
  }

  getImageQualityEnum(
    value: string,
    ignoreDefault: Boolean
  ): message.ImageQuality | undefined {
    switch (value) {
      case "low":
        return message.ImageQuality.Low;
      case "best":
        return message.ImageQuality.Best;
      case "balanced":
        return ignoreDefault ? undefined : message.ImageQuality.Balanced;
      default:
        return undefined;
    }
  }

  setImageQuality(value: string) {
    this.setOption("image-quality", value);
    const image_quality = this.getImageQualityEnum(value, false);
    if (image_quality == undefined) return;
    const option = message.OptionMessage.fromPartial({ image_quality });
    const misc = message.Misc.fromPartial({ option });
    this._ws?.sendMessage({ misc });
  }

  changePreferCodec(value: string) {
    this.setOption("codec-preference", value);
  }

  send2fa(value: string) {
    const misc = message.Misc.fromPartial({ chat_message: { text: value } });
    this._ws?.sendMessage({ misc });
  }

  elevateWithLogon(value: string) {
    try {
      const obj = JSON.parse(value);
      const misc = message.Misc.fromPartial({
        elevation_request: {
          logon: obj,
        },
      });
      this._ws?.sendMessage({ misc });
    } catch (e) {
      console.error("Failed to elevate with logon:", e);
    }
  }

  restart() {
    this.close();
    this.start(this._id);
  }

  loadVideoDecoder() {
    this._videoDecoder?.close();
    loadVp9((decoder: any) => {
      this._videoDecoder = decoder;
    });
  }
}

let _connStatus = 0;
let _videoConnCount = 0;

function testDelay() {
  if (localStorage.getItem("custom-rendezvous-server")) {
    console.log("Custom rendezvous server configured, skipping public server latency test");
    return;
  }
  
  var nearest = "";
  HOSTS.forEach((host) => {
    new Websock(getrUriFromRs(host), true).open().then(() => {
      if (!nearest) {
        HOST = host;
        localStorage.setItem("rendezvous-server", host);
      }
    });
  });
}

setTimeout(() => {
  if (!localStorage.getItem("custom-rendezvous-server")) {
    testDelay();
  } else {
    console.log("Custom rendezvous server configured, skipping public server latency test");
  }
}, 100);

function getDefaultUri(isRelay: Boolean = false): string {
  const host = localStorage.getItem("custom-rendezvous-server");
  return getrUriFromRs(host || HOST, isRelay);
}

function getrUriFromRs(
  uri: string,
  isRelay: Boolean = false,
  roffset: number = 0
): string {
  if (uri.indexOf(":") > 0) {
    const tmp = uri.split(":");
    const port = parseInt(tmp[1]);
    uri = tmp[0] + ":" + (port + (isRelay ? roffset || 3 : 2)); //no point to roffset || 3 since 5 lines above is default to 0
    return "wss://" + "fortdesk.lan";
  }
  return "wss://" + "fortdesk.lan" + (isRelay ? "/ws/relay" : "/ws/id");
}

export const PORT = 21116;

function hash(datas: (string | Uint8Array)[]): Uint8Array {
  const hasher = new sha256.Hash();
  datas.forEach((data) => {
    if (typeof data == "string") {
      data = new TextEncoder().encode(data);
    }
    return hasher.update(data);
  });
  return hasher.digest();
}
