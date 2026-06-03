/**
 * JCEF 桥核心:替代 Tauri 的 invoke / event 传输层。
 *
 * 与 Kotlin 侧(WebUiPanel)的协议:
 * - 发送:`window.__gitaiSend(JSON.stringify({type,id,cmd,args}))`;bootstrap 注入前先入队 `__gitaiQueue`。
 * - 接收:Kotlin 调 `window.__gitaiReceive({type:'response',id,ok,data|error})` / `{type:'event',channel,payload}`。
 */

declare global {
  interface Window {
    __gitaiSend?: (payload: string) => void;
    __gitaiQueue?: string[];
    __gitaiReceive?: (msg: unknown) => void;
    __GITAI_PLUGIN_VERSION__?: string;
    __GITAI_HOST__?: string;
  }
}

type Pending = { resolve: (v: unknown) => void; reject: (e: unknown) => void };
export type TauriEvent<T> = { event: string; payload: T; id?: number };
export type EventHandler<T> = (event: TauriEvent<T>) => void;
export type UnlistenFn = () => void;

const pending = new Map<string, Pending>();
const listeners = new Map<string, Set<EventHandler<unknown>>>();
let seq = 0;

function send(obj: unknown): void {
  const s = JSON.stringify(obj);
  if (typeof window.__gitaiSend === "function") {
    window.__gitaiSend(s);
  } else {
    (window.__gitaiQueue ||= []).push(s);
  }
}

// 立即安装接收端(在任何 invoke 之前),Kotlin 只负责定义 __gitaiSend + 冲刷队列。
window.__gitaiReceive = (raw: unknown) => {
  const msg = raw as { type?: string; id?: string; ok?: boolean; data?: unknown; error?: unknown; channel?: string; payload?: unknown };
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "response" && msg.id) {
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.ok) p.resolve(msg.data);
    else p.reject(typeof msg.error === "string" ? msg.error : JSON.stringify(msg.error));
  } else if (msg.type === "event" && msg.channel) {
    const set = listeners.get(msg.channel);
    if (set) set.forEach((h) => h({ event: msg.channel as string, payload: msg.payload }));
  }
};

export function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const id = "q" + ++seq;
    pending.set(id, { resolve: resolve as (v: unknown) => void, reject });
    send({ type: "invoke", id, cmd, args: args ?? {} });
  });
}

export function listen<T>(event: string, handler: EventHandler<T>): Promise<UnlistenFn> {
  let set = listeners.get(event);
  if (!set) {
    set = new Set();
    listeners.set(event, set);
  }
  set.add(handler as EventHandler<unknown>);
  send({ type: "subscribe", channel: event });
  return Promise.resolve(() => {
    set?.delete(handler as EventHandler<unknown>);
  });
}

export function once<T>(event: string, handler: EventHandler<T>): Promise<UnlistenFn> {
  const p = listen<T>(event, (e) => {
    handler(e);
    void p.then((un) => un());
  });
  return p;
}

export function emit(event: string, payload?: unknown): Promise<void> {
  send({ type: "emit", channel: event, payload });
  return Promise.resolve();
}

export {};
