import type {
  ClientMessage, ServerMessage, ChoiceRequestMsg,
  GameStateView, IntentAction, ChoiceResponse,
} from "../protocol";

interface Handlers {
  onState?: (view: GameStateView) => void;
  onEvent?: (message: string) => void;
  onChoiceRequest?: (msg: ChoiceRequestMsg) => void;
  onError?: (message: string) => void;
}

/**
 * 與 Java 權威伺服器的 WebSocket 連線。
 * 只送「意圖 / 決策回應」,只收「狀態 / 事件 / 決策請求」。
 */
export class Connection {
  private ws?: WebSocket;

  constructor(private url: string, private handlers: Handlers = {}) {}

  connect(sessionId: string, investigatorId: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => {
        this.send({ type: "JOIN", sessionId, investigatorId });
        resolve();
      };
      ws.onerror = (e) => reject(e);
      ws.onmessage = (ev) => this.handle(JSON.parse(ev.data) as ServerMessage);
    });
  }

  private handle(msg: ServerMessage) {
    switch (msg.type) {
      case "STATE": this.handlers.onState?.(msg.view); break;
      case "EVENT": this.handlers.onEvent?.(msg.message); break;
      case "CHOICE_REQUEST": this.handlers.onChoiceRequest?.(msg); break;
      case "ERROR": this.handlers.onError?.(msg.message); break;
      case "PONG": break;
    }
  }

  send(msg: ClientMessage) { this.ws?.send(JSON.stringify(msg)); }

  intent(action: IntentAction, payload?: Record<string, unknown>) {
    this.send({ type: "INTENT", action, payload });
  }
  respond(requestId: string, choice: ChoiceResponse) {
    this.send({ type: "CHOICE_RESPONSE", requestId, choice });
  }
}
