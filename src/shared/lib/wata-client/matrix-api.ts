/**
 * Typed Matrix Client-Server API implementation
 *
 * This module provides a minimal, typed HTTP client for the Matrix Client-Server API.
 * It covers only the endpoints needed by WataClient (see docs/planning/client-lib.md).
 *
 * Authentication:
 * - After login, the access token is stored and included in all subsequent requests
 * - All authenticated endpoints use Authorization: Bearer {token} header
 */

// ============================================================================
// Request/Response Types
// ============================================================================

// --- Authentication ---

export interface LoginRequest {
  type: 'm.login.password';
  identifier: {
    type: 'm.id.user';
    user: string;
  };
  password: string;
  initial_device_display_name?: string;
}

export interface LoginResponse {
  user_id: string;
  access_token: string;
  device_id: string;
  home_server?: string;
  refresh_token?: string;
  expires_in_ms?: number;
  well_known?: {
    'm.homeserver'?: {
      base_url: string;
    };
  };
}

export interface WhoamiResponse {
  user_id: string;
  device_id?: string;
  is_guest?: boolean;
}

export interface LogoutRequest {
  // Empty body
}

export interface LogoutResponse {
  // Empty response
}

// --- Sync ---

export interface SyncParams {
  filter?: string;
  since?: string;
  full_state?: boolean;
  set_presence?: 'offline' | 'online' | 'unavailable';
  timeout?: number; // milliseconds
}

export interface SyncResponse {
  next_batch: string;
  rooms?: {
    join?: Record<string, JoinedRoomSync>;
    invite?: Record<string, InvitedRoomSync>;
    leave?: Record<string, LeftRoomSync>;
  };
  presence?: {
    events: MatrixEvent[];
  };
  account_data?: {
    events: MatrixEvent[];
  };
  to_device?: {
    events: MatrixEvent[];
  };
}

export interface RoomSummary {
  'm.heroes'?: string[];
  'm.joined_member_count'?: number;
  'm.invited_member_count'?: number;
}

export interface JoinedRoomSync {
  summary?: RoomSummary;
  state?: {
    events: MatrixEvent[];
  };
  state_after?: {
    events: MatrixEvent[];
  };
  timeline?: {
    events: MatrixEvent[];
    limited: boolean;
    prev_batch: string;
  };
  ephemeral?: {
    events: MatrixEvent[];
  };
  account_data?: {
    events: MatrixEvent[];
  };
  unread_notifications?: {
    highlight_count: number;
    notification_count: number;
  };
}

export interface InvitedRoomSync {
  invite_state?: {
    events: StrippedStateEvent[];
  };
}

export interface LeftRoomSync {
  state?: {
    events: MatrixEvent[];
  };
  timeline?: {
    events: MatrixEvent[];
    limited: boolean;
    prev_batch: string;
  };
}

export interface MatrixEvent {
  type: string;
  event_id?: string;
  sender?: string;
  origin_server_ts?: number;
  unsigned?: {
    age?: number;
    redacted_because?: MatrixEvent;
    transaction_id?: string;
  };
  content: Record<string, any>;
  state_key?: string;
  room_id?: string;
}

export interface StrippedStateEvent {
  type: string;
  state_key: string;
  content: Record<string, any>;
  sender: string;
  event_id?: string;
  origin_server_ts?: number;
}

// --- Rooms ---

export interface CreateRoomRequest {
  visibility?: 'public' | 'private';
  room_alias_name?: string;
  name?: string;
  topic?: string;
  invite?: string[];
  invite_3pid?: Array<{
    id_server: string;
    id_access_token: string;
    medium: string;
    address: string;
  }>;
  room_version?: string;
  creation_content?: Record<string, any>;
  initial_state?: Array<{
    type: string;
    state_key?: string;
    content: Record<string, any>;
  }>;
  preset?: 'private_chat' | 'trusted_private_chat' | 'public_chat';
  is_direct?: boolean;
  power_level_content_override?: Record<string, any>;
}

export interface CreateRoomResponse {
  room_id: string;
}

export interface JoinRoomRequest {
  third_party_signed?: {
    sender: string;
    mxid: string;
    token: string;
    signatures: Record<string, Record<string, string>>;
  };
}

export interface JoinRoomResponse {
  room_id: string;
}

export interface InviteRequest {
  user_id: string;
  reason?: string;
}

export interface InviteResponse {
  // Empty response
}

export interface RoomAliasResponse {
  room_id: string;
  servers: string[];
}

// --- Messages ---

export interface SendMessageRequest {
  // Content varies by event type
  msgtype?: string;
  body?: string;
  url?: string;
  info?: Record<string, any>;
  [key: string]: any;
}

export interface SendMessageResponse {
  event_id: string;
}

export interface RedactRequest {
  reason?: string;
}

export interface RedactResponse {
  event_id: string;
}

export interface ReceiptRequest {
  // Empty body or thread_id
  thread_id?: string;
}

export interface ReceiptResponse {
  // Empty response
}

// --- Media ---

export interface UploadResponse {
  content_uri: string; // mxc:// URL
}

// Download returns raw ArrayBuffer

// --- Profile & Account Data ---

export interface ProfileResponse {
  avatar_url?: string;
  displayname?: string;
  'm.tz'?: string;
}

export interface SetDisplayNameRequest {
  displayname: string;
}

export interface SetDisplayNameResponse {
  // Empty response
}

export interface AccountDataResponse {
  // Content varies by type
  [key: string]: any;
}

export interface SetAccountDataRequest {
  // Content varies by type
  [key: string]: any;
}

export interface SetAccountDataResponse {
  // Empty response
}

// ============================================================================
// Matrix API Client
// ============================================================================

export class MatrixApi {
  private baseUrl: string;
  private accessToken: string | null = null;
  private txnCounter = 0;

  constructor(baseUrl: string) {
    // Normalize base URL (remove trailing slash)
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  /**
   * Generate a unique transaction ID for send/redact operations
   */
  private generateTxnId(): string {
    return `wata-${Date.now()}-${this.txnCounter++}`;
  }

  /**
   * Make an authenticated HTTP request
   */
  private async request<T>(
    method: string,
    path: string,
    options: {
      body?: any;
      params?: Record<string, string | number | boolean>;
      requireAuth?: boolean;
      contentType?: string;
    } = {}
  ): Promise<T> {
    const { body, params, requireAuth = true, contentType } = options;

    // Build URL with query parameters
    let url = `${this.baseUrl}${path}`;
    if (params) {
      const searchParams = new URLSearchParams();
      Object.entries(params).forEach(([key, value]) => {
        searchParams.append(key, String(value));
      });
      url += `?${searchParams.toString()}`;
    }

    // Build headers
    const headers: Record<string, string> = {};

    if (requireAuth) {
      if (!this.accessToken) {
        throw new Error('Not authenticated - access token required');
      }
      headers['Authorization'] = `Bearer ${this.accessToken}`;
    }

    if (body !== undefined) {
      if (contentType) {
        headers['Content-Type'] = contentType;
      } else if (typeof body === 'string' || body instanceof ArrayBuffer) {
        // Raw body (for media upload)
        headers['Content-Type'] = 'application/octet-stream';
      } else {
        // JSON body
        headers['Content-Type'] = 'application/json';
      }
    }

    // Make request
    const response = await fetch(url, {
      method,
      headers,
      body:
        body instanceof ArrayBuffer
          ? body
          : body !== undefined
            ? JSON.stringify(body)
            : undefined,
    });

    // Handle errors
    if (!response.ok) {
      let errorMessage = `HTTP ${response.status}: ${response.statusText}`;
      try {
        const errorBody = await response.json();
        if (errorBody.error) {
          errorMessage += ` - ${errorBody.error}`;
        }
        if (errorBody.errcode) {
          errorMessage = `${errorBody.errcode}: ${errorBody.error || errorMessage}`;
        }
      } catch {
        // Failed to parse error body, use status text
      }
      throw new Error(errorMessage);
    }

    // Parse response
    const contentTypeHeader = response.headers.get('content-type');
    if (contentTypeHeader?.includes('application/json')) {
      return response.json() as Promise<T>;
    } else if (
      contentTypeHeader?.includes('application/octet-stream') ||
      contentTypeHeader?.startsWith('audio/') ||
      contentTypeHeader?.startsWith('video/') ||
      contentTypeHeader?.startsWith('image/')
    ) {
      return response.arrayBuffer() as Promise<T>;
    } else {
      // Empty response or unknown content type
      return {} as T;
    }
  }

  // ==========================================================================
  // Authentication
  // ==========================================================================

  /**
   * Login with username and password
   * Stores access token for subsequent requests
   */
  async login(
    username: string,
    password: string,
    deviceDisplayName?: string
  ): Promise<LoginResponse> {
    const response = await this.request<LoginResponse>(
      'POST',
      '/_matrix/client/v3/login',
      {
        requireAuth: false,
        body: {
          type: 'm.login.password',
          identifier: {
            type: 'm.id.user',
            user: username,
          },
          password,
          initial_device_display_name: deviceDisplayName,
        } satisfies LoginRequest,
      }
    );

    // Store access token for future requests
    this.accessToken = response.access_token;

    return response;
  }

  /**
   * Logout and invalidate access token
   */
  async logout(): Promise<void> {
    await this.request<LogoutResponse>('POST', '/_matrix/client/v3/logout', {
      body: {} satisfies LogoutRequest,
    });

    // Clear access token
    this.accessToken = null;
  }

  /**
   * Get information about the owner of an access token
   */
  async whoami(): Promise<WhoamiResponse> {
    return this.request<WhoamiResponse>(
      'GET',
      '/_matrix/client/v3/account/whoami'
    );
  }

  /**
   * Set access token manually (for resuming sessions)
   */
  setAccessToken(token: string): void {
    this.accessToken = token;
  }

  /**
   * Get current access token
   */
  getAccessToken(): string | null {
    return this.accessToken;
  }

  // ==========================================================================
  // Sync
  // ==========================================================================

  /**
   * Long-poll for events and state changes
   */
  async sync(params: SyncParams = {}): Promise<SyncResponse> {
    const queryParams: Record<string, string | number | boolean> = {};

    if (params.filter !== undefined) queryParams.filter = params.filter;
    if (params.since !== undefined) queryParams.since = params.since;
    if (params.full_state !== undefined)
      queryParams.full_state = params.full_state;
    if (params.set_presence !== undefined)
      queryParams.set_presence = params.set_presence;
    if (params.timeout !== undefined) queryParams.timeout = params.timeout;

    return this.request<SyncResponse>('GET', '/_matrix/client/v3/sync', {
      params: queryParams,
    });
  }

  // ==========================================================================
  // Rooms
  // ==========================================================================

  /**
   * Create a new room
   */
  async createRoom(request: CreateRoomRequest): Promise<CreateRoomResponse> {
    return this.request<CreateRoomResponse>(
      'POST',
      '/_matrix/client/v3/createRoom',
      {
        body: request,
      }
    );
  }

  /**
   * Join a room by ID or alias
   */
  async joinRoom(
    roomIdOrAlias: string,
    request: JoinRoomRequest = {}
  ): Promise<JoinRoomResponse> {
    return this.request<JoinRoomResponse>(
      'POST',
      `/_matrix/client/v3/join/${encodeURIComponent(roomIdOrAlias)}`,
      {
        body: request,
      }
    );
  }

  /**
   * Invite a user to a room
   */
  async inviteToRoom(
    roomId: string,
    request: InviteRequest
  ): Promise<InviteResponse> {
    return this.request<InviteResponse>(
      'POST',
      `/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/invite`,
      {
        body: request,
      }
    );
  }

  /**
   * Kick a user from a room
   */
  async kickFromRoom(
    roomId: string,
    userId: string,
    reason?: string
  ): Promise<void> {
    await this.request<Record<string, never>>(
      'POST',
      `/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/kick`,
      {
        body: { user_id: userId, reason },
      }
    );
  }

  /**
   * Resolve room alias to room ID
   */
  async getRoomIdForAlias(roomAlias: string): Promise<RoomAliasResponse> {
    return this.request<RoomAliasResponse>(
      'GET',
      `/_matrix/client/v3/directory/room/${encodeURIComponent(roomAlias)}`
    );
  }

  // ==========================================================================
  // Messages
  // ==========================================================================

  /**
   * Send a message event to a room
   */
  async sendMessage(
    roomId: string,
    eventType: string,
    content: SendMessageRequest,
    txnId?: string
  ): Promise<SendMessageResponse> {
    const txn = txnId || this.generateTxnId();
    return this.request<SendMessageResponse>(
      'PUT',
      `/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/send/${encodeURIComponent(eventType)}/${encodeURIComponent(txn)}`,
      {
        body: content,
      }
    );
  }

  /**
   * Redact (delete) an event
   */
  async redactEvent(
    roomId: string,
    eventId: string,
    reason?: string,
    txnId?: string
  ): Promise<RedactResponse> {
    const txn = txnId || this.generateTxnId();
    return this.request<RedactResponse>(
      'PUT',
      `/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/redact/${encodeURIComponent(eventId)}/${encodeURIComponent(txn)}`,
      {
        body: {
          reason,
        } satisfies RedactRequest,
      }
    );
  }

  /**
   * Send a read receipt for an event
   */
  async sendReadReceipt(
    roomId: string,
    eventId: string,
    threadId?: string
  ): Promise<void> {
    await this.request<ReceiptResponse>(
      'POST',
      `/_matrix/client/v3/rooms/${encodeURIComponent(roomId)}/receipt/m.read/${encodeURIComponent(eventId)}`,
      {
        body: threadId ? ({ thread_id: threadId } satisfies ReceiptRequest) : {},
      }
    );
  }

  // ==========================================================================
  // Media
  // ==========================================================================

  /**
   * Upload a file to the media repository
   * @param data - File data as ArrayBuffer
   * @param contentType - MIME type (e.g., 'audio/mp4')
   * @param filename - Optional filename
   */
  async uploadMedia(
    data: ArrayBuffer,
    contentType: string,
    filename?: string
  ): Promise<UploadResponse> {
    let path = '/_matrix/media/v3/upload';
    if (filename) {
      path += `?filename=${encodeURIComponent(filename)}`;
    }

    return this.request<UploadResponse>('POST', path, {
      body: data,
      contentType,
    });
  }

  /**
   * Download a file from the media repository
   * @param mxcUrl - MXC URL (mxc://server/mediaId)
   * @returns File data as ArrayBuffer
   */
  async downloadMedia(mxcUrl: string): Promise<ArrayBuffer> {
    // Parse mxc:// URL
    const match = mxcUrl.match(/^mxc:\/\/([^/]+)\/(.+)$/);
    if (!match) {
      throw new Error(`Invalid MXC URL: ${mxcUrl}`);
    }

    const [, serverName, mediaId] = match;

    return this.request<ArrayBuffer>(
      'GET',
      `/_matrix/client/v1/media/download/${encodeURIComponent(serverName)}/${encodeURIComponent(mediaId)}`
    );
  }

  // ==========================================================================
  // Profile & Account Data
  // ==========================================================================

  /**
   * Get user profile (display name and avatar)
   */
  async getProfile(userId: string): Promise<ProfileResponse> {
    return this.request<ProfileResponse>(
      'GET',
      `/_matrix/client/v3/profile/${encodeURIComponent(userId)}`
    );
  }

  /**
   * Set display name for current user
   */
  async setDisplayName(
    userId: string,
    displayName: string
  ): Promise<void> {
    await this.request<SetDisplayNameResponse>(
      'PUT',
      `/_matrix/client/v3/profile/${encodeURIComponent(userId)}/displayname`,
      {
        body: {
          displayname: displayName,
        } satisfies SetDisplayNameRequest,
      }
    );
  }

  /**
   * Set avatar URL for current user
   */
  async setAvatarUrl(userId: string, avatarUrl: string): Promise<void> {
    await this.request<SetDisplayNameResponse>(
      'PUT',
      `/_matrix/client/v3/profile/${encodeURIComponent(userId)}/avatar_url`,
      {
        body: {
          avatar_url: avatarUrl,
        },
      }
    );
  }

  /**
   * Get account data for current user
   */
  async getAccountData(
    userId: string,
    type: string
  ): Promise<AccountDataResponse> {
    return this.request<AccountDataResponse>(
      'GET',
      `/_matrix/client/v3/user/${encodeURIComponent(userId)}/account_data/${encodeURIComponent(type)}`
    );
  }

  /**
   * Set account data for current user
   */
  async setAccountData(
    userId: string,
    type: string,
    content: Record<string, any>
  ): Promise<void> {
    await this.request<SetAccountDataResponse>(
      'PUT',
      `/_matrix/client/v3/user/${encodeURIComponent(userId)}/account_data/${encodeURIComponent(type)}`,
      {
        body: content,
      }
    );
  }

  /**
   * Get room-specific account data for current user
   */
  async getRoomAccountData(
    userId: string,
    roomId: string,
    type: string
  ): Promise<AccountDataResponse> {
    return this.request<AccountDataResponse>(
      'GET',
      `/_matrix/client/v3/user/${encodeURIComponent(userId)}/rooms/${encodeURIComponent(roomId)}/account_data/${encodeURIComponent(type)}`
    );
  }

  /**
   * Set room-specific account data for current user
   */
  async setRoomAccountData(
    userId: string,
    roomId: string,
    type: string,
    content: Record<string, any>
  ): Promise<void> {
    await this.request<SetAccountDataResponse>(
      'PUT',
      `/_matrix/client/v3/user/${encodeURIComponent(userId)}/rooms/${encodeURIComponent(roomId)}/account_data/${encodeURIComponent(type)}`,
      {
        body: content,
      }
    );
  }
}
