declare module 'sockjs-client' {
  class SockJS {
    constructor(url: string, protocols?: string | string[], options?: object);
    send(data: string | object): void;
    close(code?: number, reason?: string): void;
    onopen: () => void;
    onclose: (event: CloseEvent) => void;
    onmessage: (event: MessageEvent) => void;
    onerror: (event: Event) => void;
    readyState: number;
    protocol: string;
    url: string;
  }

  namespace SockJS {
    const CONNECTING: 0;
    const OPEN: 1;
    const CLOSING: 2;
    const CLOSED: 3;
  }

  export default SockJS;
}
