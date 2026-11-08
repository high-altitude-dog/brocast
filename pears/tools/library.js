// The test library: three 6-minute sets of stereo pink noise, at the station's settings (160 kbps VBR Opus, 20 ms
// frames). Made locally and only ever sent as Opus blocks, so no raw audio crosses any network.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

export function makeLibrary (library) {
  fs.mkdirSync(library, { recursive: true })
  for (const n of [1, 2, 3]) {
    const f = path.join(library, `set${n}.opus`)
    if (fs.existsSync(f)) continue
    execFileSync('nice', ['-n', '19', 'ffmpeg', '-nostdin', '-v', 'error', '-y',
      '-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=0.25:duration=360:seed=${n}`,
      '-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=0.25:duration=360:seed=${n + 10}`,
      '-filter_complex', 'amerge=inputs=2', '-ar', '48000', '-c:a', 'libopus', '-b:a', '160k', '-vbr', 'on',
      '-frame_duration', '20', '-page_duration', '1000000', '-f', 'ogg', f])
  }
}
