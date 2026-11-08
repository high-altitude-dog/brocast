// Node globals the engine uses, for Bare. Imported first, so they exist before any engine module runs.
import Buffer from 'bare-buffer'

if (typeof globalThis.Buffer === 'undefined') globalThis.Buffer = Buffer
