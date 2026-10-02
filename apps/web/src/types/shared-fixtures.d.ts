// The worked examples of PDF box geometry live in the repository's shared fixtures, outside apps/web,
// so the server's tests and the web app's read the same file. The web image is built from apps/web
// alone, where that file is not; this declaration lets its type check pass there. Wherever the file
// is present, as in every test run, the file itself gives the type.
//
// Like jest-axe.d.ts, this file must stay a global script (no top-level import/export).
declare module '*/fixtures/public/pdf-geometry-vectors.json' {
  const vectors: { about: string[]; cases: unknown[] }
  export default vectors
}
