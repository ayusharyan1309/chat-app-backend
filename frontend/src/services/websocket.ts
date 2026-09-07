import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { ChatMessage, TypingIndicator } from '../types';

const WS_URL = import.meta.env.VITE_WS_URL || 'http://localhost:8080/ws';

type MessageCallback = (message: ChatMessage) => void;
type TypingCallback = (indicator: TypingIndicator) => void;
type ConnectionCallback = (connected: boolean) => void;

/**
 * WebSocket service for real-time chat communication.
 * Uses STOMP over SockJS for reliable message delivery.
 */
class WebSocketService {
  private client: Client | null = null;
  private subscriptions: Map<string, StompSubscription> = new Map();
  private onMessageCallback: MessageCallback | null = null;
  private onTypingCallback: TypingCallback | null = null;
  private onConnectionCallback: ConnectionCallback | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private currentUserEmail: string | null = null;

  /**
   * Connect to the WebSocket server.
   */
  connect(userEmail: string, token: string): Promise<void> {
    this.currentUserEmail = userEmail;

    return new Promise((resolve, reject) => {
      this.client = new Client({
        webSocketFactory: () => new SockJS(WS_URL) as unknown as WebSocket,
        connectHeaders: {
          Authorization: `Bearer ${token}`,
        },
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        reconnectDelay: 5000,
        onConnect: () => {
          console.log('[WS] Connected');
          this.reconnectAttempts = 0;
          this.onConnectionCallback?.(true);
          this.subscribeToUserQueue();
          resolve();
        },
        onDisconnect: () => {
          console.log('[WS] Disconnected');
          this.onConnectionCallback?.(false);
        },
        onStompError: (frame) => {
          console.error('[WS] STOMP error:', frame.headers['message']);
          console.error('[WS] Details:', frame.body);
          this.reconnectAttempts++;
          if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            reject(new Error('Max reconnect attempts reached'));
          }
        },
        onWebSocketError: (error) => {
          console.error('[WS] WebSocket error:', error);
          this.onConnectionCallback?.(false);
        },
      });

      this.client.activate();
    });
  }

  /**
   * Disconnect from the WebSocket server.
   */
  disconnect(): void {
    this.subscriptions.forEach((sub) => sub.unsubscribe());
    this.subscriptions.clear();
    this.client?.deactivate();
    this.client = null;
    this.currentUserEmail = null;
  }

  /**
   * Subscribe to the user's private message queue.
   */
  private subscribeToUserQueue(): void {
    if (!this.client || !this.currentUserEmail) return;

    // Subscribe to incoming messages
    const msgSub = this.client.subscribe(
      '/user/queue/messages',
      (message: IMessage) => {
        try {
          const chatMessage: ChatMessage = JSON.parse(message.body);
          this.onMessageCallback?.(chatMessage);
        } catch (e) {
          console.error('[WS] Failed to parse message:', e);
        }
      },
      { chatWithEmail: this.currentUserEmail }
    );
    this.subscriptions.set('/user/queue/messages', msgSub);

    // Subscribe to typing indicators
    const typingSub = this.client.subscribe(
      '/user/queue/typing',
      (message: IMessage) => {
        try {
          const indicator: TypingIndicator = JSON.parse(message.body);
          this.onTypingCallback?.(indicator);
        } catch (e) {
          console.error('[WS] Failed to parse typing indicator:', e);
        }
      }
    );
    this.subscriptions.set('/user/queue/typing', typingSub);
  }

  /**
   * Subscribe to a specific conversation's messages.
   */
  subscribeToConversation(
    conversationId: string,
    chatWithEmail: string,
    callback: (message: ChatMessage) => void
  ): void {
    if (!this.client) return;

    const dest = `/topic/conversation/${conversationId}`;
    const sub = this.client.subscribe(dest, (message: IMessage) => {
      try {
        const chatMessage: ChatMessage = JSON.parse(message.body);
        callback(chatMessage);
      } catch (e) {
        console.error('[WS] Failed to parse conversation message:', e);
      }
    }, { chatWithEmail });
    this.subscriptions.set(dest, sub);
  }

  /**
   * Unsubscribe from a conversation.
   */
  unsubscribeFromConversation(conversationId: string): void {
    const dest = `/topic/conversation/${conversationId}`;
    this.subscriptions.get(dest)?.unsubscribe();
    this.subscriptions.delete(dest);
  }

  /**
   * Send a chat message.
   */
  sendMessage(
    recipientEmail: string,
    content: string,
    messageType: string = 'TEXT'
  ): void {
    if (!this.client || !this.client.connected) {
      console.error('[WS] Cannot send message: not connected');
      return;
    }

    this.client.publish({
      destination: '/app/chat.send',
      body: JSON.stringify({
        recipientEmail,
        content,
        messageType,
        isCrossPlatformMessage: false,
        isAppToWeb: false,
      }),
    });
  }

  /**
   * Send a typing indicator.
   */
  sendTypingIndicator(recipientEmail: string, typing: boolean): void {
    if (!this.client || !this.client.connected) return;

    this.client.publish({
      destination: '/app/chat.typing',
      body: JSON.stringify({
        recipientUsername: recipientEmail,
        typing,
      }),
    });
  }

  /**
   * Set the callback for incoming messages.
   */
  onMessage(callback: MessageCallback): void {
    this.onMessageCallback = callback;
  }

  /**
   * Set the callback for typing indicators.
   */
  onTyping(callback: TypingCallback): void {
    this.onTypingCallback = callback;
  }

  /**
   * Set the callback for connection status changes.
   */
  onConnectionChange(callback: ConnectionCallback): void {
    this.onConnectionCallback = callback;
  }

  /**
   * Check if currently connected.
   */
  get isConnected(): boolean {
    return this.client?.connected ?? false;
  }
}

// Singleton instance
export const wsService = new WebSocketService();
export default wsService;
