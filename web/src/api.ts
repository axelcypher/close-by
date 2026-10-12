// Mirrors shared/src/main/kotlin/net/pendzialek/closeby/api/Api.kt; keep both in sync.

export type DeviceKind = "frame" | "phone" | "desktop";

export interface AuthResponse { token: string; deviceId: string; personId?: string }
export interface PairingStartResponse { pairingId: string; code: string; pollSecret: string; expiresAt: number }
export interface PairingStatus { state: "pending" | "done"; token?: string; deviceId?: string; personId?: string }
export interface InviteResponse { code: string; url: string; expiresAt: number }

export interface DeviceStatus {
  battery?: number;
  charging?: boolean;
  wifiRssi?: number;
  deviceOwner?: boolean;
  kiosk?: boolean;
}
export interface DeviceInfo {
  id: string;
  kind: DeviceKind;
  name: string;
  personId?: string;
  appVersion?: number;
  lastSeen?: number;
  online: boolean;
  status?: DeviceStatus;
}
export interface PersonInfo { id: string; name: string; displayName?: string; isAdmin: boolean; avatarVersion?: number }
export interface Me { device: DeviceInfo; person?: PersonInfo }

export interface FrameSettings {
  slideshowIntervalSeconds: number;
  idleTimeoutSeconds: number;
  nightStart?: string | null;
  nightEnd?: string | null;
  volume?: number | null;
  brightness?: number | null;
  forcePublicDns: boolean;
}
export interface FrameRef { id: string; name: string; online: boolean }
export interface FrameContactEntry { personId: string; autoAnswer: boolean }
export interface FrameAdmin { device: DeviceInfo; albumId?: string; settings: FrameSettings; contacts: FrameContactEntry[] }
export interface PersonAdmin { person: PersonInfo; devices: DeviceInfo[] }
export interface ImmichAlbum { id: string; name: string; assetCount: number }

export interface CallParty { kind: "person" | "frame"; id: string; name: string }
export interface CallJoin { callId: string; livekitUrl: string; token: string }

export interface ServerEvent {
  type: string;
  callId?: string;
  from?: CallParty;
  autoAnswer?: boolean;
  reason?: string;
  deviceId?: string;
  online?: boolean;
}

const TOKEN_KEY = "closeby.token";

export function storedToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function storeToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // private mode: the session still works until reload
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  const token = storedToken();
  const response = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined && !(body instanceof Blob) ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body),
  });
  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try {
      message = JSON.parse(text).error ?? text;
    } catch {
      // plain text error
    }
    throw new ApiError(response.status, message || response.statusText);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  register: (body: { code?: string; adminCode?: string; device: { kind: DeviceKind; name: string }; person: { name: string } }) =>
    request<AuthResponse>("POST", "/api/register", body),
  startPairing: (kind: DeviceKind, name: string) => request<PairingStartResponse>("POST", "/api/pairing/start", { kind, name }),
  pollPairing: (id: string, pollSecret: string) =>
    request<PairingStatus>("GET", `/api/pairing/${id}`, undefined, { "X-Poll-Secret": pollSecret }),
  claimPairing: (code: string) => request<{ deviceId: string; kind: DeviceKind }>("POST", "/api/pairing/claim", { code }),

  me: () => request<Me>("GET", "/api/me"),
  signOut: () => request<void>("DELETE", "/api/me"),
  rename: (name: string) => request<void>("PATCH", "/api/me", { name }),
  uploadAvatar: (image: Blob) => request<void>("PUT", "/api/me/avatar", image, { "Content-Type": image.type }),
  frames: () => request<FrameRef[]>("GET", "/api/frames"),

  startCall: (frameId: string) => request<CallJoin>("POST", "/api/calls", { frameId }),
  acceptCall: (callId: string) => request<CallJoin>("POST", `/api/calls/${callId}/accept`),
  declineCall: (callId: string) => request<void>("POST", `/api/calls/${callId}/decline`),
  endCall: (callId: string) => request<void>("POST", `/api/calls/${callId}/end`),

  admin: {
    frames: () => request<FrameAdmin[]>("GET", "/api/admin/frames"),
    updateFrame: (id: string, body: { name: string; albumId?: string | null; settings: FrameSettings }) =>
      request<void>("PUT", `/api/admin/frames/${id}`, body),
    setFrameContacts: (id: string, contacts: FrameContactEntry[]) => request<void>("PUT", `/api/admin/frames/${id}/contacts`, contacts),
    persons: () => request<PersonAdmin[]>("GET", "/api/admin/persons"),
    updatePerson: (id: string, body: { displayName?: string | null; isAdmin: boolean }) =>
      request<void>("PUT", `/api/admin/persons/${id}`, body),
    deletePerson: (id: string) => request<void>("DELETE", `/api/admin/persons/${id}`),
    deleteDevice: (id: string) => request<void>("DELETE", `/api/admin/devices/${id}`),
    frameCommand: (id: string, command: "maintenance" | "update" | "release") =>
      request<void>("POST", `/api/admin/frames/${id}/command`, { command }),
    invite: () => request<InviteResponse>("POST", "/api/admin/invites"),
    albums: () => request<ImmichAlbum[]>("GET", "/api/admin/immich/albums"),
  },
};

/** Avatars need the bearer token, so <img src> can't load them directly. */
const avatarCache = new Map<string, Promise<string | null>>();

export function avatarUrl(personId: string, version?: number | null): Promise<string | null> {
  if (version == null) return Promise.resolve(null);
  const key = `${personId}:${version}`;
  let url = avatarCache.get(key);
  if (!url) {
    const token = storedToken();
    url = fetch(`/api/persons/${personId}/avatar?v=${version}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then((r) => (r.ok ? r.blob() : null))
      .then((blob) => (blob ? URL.createObjectURL(blob) : null))
      .catch(() => null);
    avatarCache.set(key, url);
  }
  return url;
}

export function formatCode(code: string) {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}
