import jwt from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET || 'dev-secret-change-me';
const TOKEN_EXPIRY = '24h';

interface TokenPayload {
  clientId: string;
  iat?: number;
  exp?: number;
}

export function generateToken(clientId: string): string {
  return jwt.sign({ clientId }, JWT_SECRET, { expiresIn: TOKEN_EXPIRY });
}

export function validateToken(token: string): TokenPayload | null {
  try {
    return jwt.verify(token, JWT_SECRET) as TokenPayload;
  } catch {
    return null;
  }
}
