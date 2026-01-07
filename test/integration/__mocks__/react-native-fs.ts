// Mock for react-native-fs in Node.js tests
import * as fs from 'fs';
import * as os from 'os';

export const CachesDirectoryPath = os.tmpdir();
export const DocumentDirectoryPath = os.tmpdir();

export async function readFile(
  filepath: string,
  encoding?: string,
): Promise<string> {
  return fs.readFileSync(filepath, (encoding as BufferEncoding) || 'utf8');
}

export async function writeFile(
  filepath: string,
  contents: string,
  encoding?: string,
): Promise<void> {
  fs.writeFileSync(filepath, contents, encoding as BufferEncoding);
}

export async function unlink(filepath: string): Promise<void> {
  try {
    fs.unlinkSync(filepath);
  } catch {
    // Ignore if file doesn't exist
  }
}

export async function stat(
  filepath: string,
): Promise<{ size: number; isFile: () => boolean }> {
  const stats = fs.statSync(filepath);
  return {
    size: stats.size,
    isFile: () => stats.isFile(),
  };
}

export async function exists(filepath: string): Promise<boolean> {
  return fs.existsSync(filepath);
}

export default {
  CachesDirectoryPath,
  DocumentDirectoryPath,
  readFile,
  writeFile,
  unlink,
  stat,
  exists,
};
