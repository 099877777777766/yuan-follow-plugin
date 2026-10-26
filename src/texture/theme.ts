export const defaultPalette = {
  bg: '#edf3f1', surface: '#ffffff', soft: '#deede7', line: '#d7e3df',
  ink: '#223d36', muted: '#657c74', accent: '#337764', on: '#ffffff',
  tableLine: '#9bb9af', stripe: '#d2e2dc', placeholder: '#c6d9d1',
  watermark: 'rgba(34,61,54,0.14)',
  card: '#f8fcfa', cardAlt: '#eaf2ee',
} as const;

export const validThemeColor = (value: unknown): value is string => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value);
export function colorChannels(color: string): number[] {
  return [1, 3, 5].map(start => parseInt(color.slice(start, start + 2), 16));
}
