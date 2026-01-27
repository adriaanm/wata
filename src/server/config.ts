export interface UserConfig {
  localpart: string;
  password: string;
  displayName: string;
}

export interface ServerConfig {
  serverName: string;
  port: number;
  users: UserConfig[];
}
