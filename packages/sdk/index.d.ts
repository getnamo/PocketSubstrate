export type Capability = 'storage' | 'haptics' | 'motion' | 'location' | 'network';
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface Substrate {
  call<T extends Json = Json>(method: string, params?: Record<string, Json>): Promise<T>;
  backend: { call<T extends Json = Json>(method: string, params?: Record<string, Json>): Promise<T> };
  storage: { get(key: string): Promise<Json>; set(key: string, value: Json): Promise<Json> };
  haptics: { pulse(milliseconds?: number): Promise<Json> };
  location: { current(): Promise<Json> };
  motion: { start(): Promise<Json>; stop(): Promise<Json> };
  network: { fetch(url: string): Promise<Json> };
  capabilities(): Promise<Json>;
  on(event: string, callback: (value: Json) => void): () => void;
}
declare global { interface Window { substrate: Substrate } }
