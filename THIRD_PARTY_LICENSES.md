# Third-party code

AntiFreecam Fabric is licensed under the GNU Affero General Public License v3.0 or later (see `LICENSE`).
It is built from the following projects; their licenses and copyright notices are kept here.

## PlayerCulling — AGPL-3.0-or-later

https://github.com/MinceraftMC/PlayerCulling — by pianoman911 and booky10.
Player and entity culling (`io.github.sanchous327.antifreecam.culling`, `...mixin.culling`) is a modified version of it.

## OcclusionCulling — MIT

https://github.com/LogisticsCraft/OcclusionCulling — the occlusion raycasting in
`culling/occlusion/OcclusionCullingInstance.java` (via PlayerCulling).

## AntiXray (Fabric) — MIT

https://github.com/DrexHD/AntiXray — a port of Paper's Anti-Xray patch.
Block hiding (`io.github.sanchous327.antifreecam.blocks`, `...mixin.blocks`) is a modified version of it.

```
MIT License

Copyright (c) 2021 Drex

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The OcclusionCulling MIT notice is the same text with the copyright of its authors
(see the LICENSE file of https://github.com/LogisticsCraft/OcclusionCulling).

## Bundled libraries

- toml4j — https://github.com/mwanji/toml4j — MIT
- fabric-permissions-api — https://github.com/lucko/fabric-permissions-api — MIT
