// Mock for react-native-keychain in Node.js tests

const storage: Map<string, {username: string; password: string}> = new Map();

export async function setGenericPassword(
  username: string,
  password: string,
  options?: {service?: string},
): Promise<boolean> {
  const key = options?.service || 'default';
  storage.set(key, {username, password});
  return true;
}

export async function getGenericPassword(options?: {
  service?: string;
}): Promise<{username: string; password: string} | false> {
  const key = options?.service || 'default';
  return storage.get(key) || false;
}

export async function resetGenericPassword(options?: {
  service?: string;
}): Promise<boolean> {
  const key = options?.service || 'default';
  storage.delete(key);
  return true;
}
