# Trowel

Trowel is a building toolkit for Paper 26.2: a home-made FastAsyncWorldEdit with the best of
ezEdits, Arceon, goPaint, goBrush and VoxelSniper. Selections, patterns, palettes, masks, noises,
splines, math expressions, sculpting, textures, brushes, a shared schematic library and
quick-action dialogs on the **G** key.

Everything works **by command** (`//set stone`, like WorldEdit) **or by window** (**G** key).
Computing runs off the main thread; only block placement happens on the server thread, spread
over ticks, so the server never freezes.

Other plugins can decide where and by whom Trowel may be used through a small API (see
[For plugin developers](#for-plugin-developers)). Celeste, for instance, restricts it to the
buildable area of a player's own plot.

---

## Installation

- Paper 26.2, Java 25.
- Drop `Trowel-1.0.0.jar` into `plugins/`.
- Optional: [AxiomPaper](https://axiom.moulberry.com/) for the Axiom client mod (see [Axiom](#axiom)).

Build from source:

```bash
mvn clean install
```

### Configuration (`plugins/Trowel/config.yml`)

| Key | Default | Meaning |
|-----|---------|---------|
| `limits.max-blocks` | 50000 | Blocks a single operation may change |
| `limits.blocks-per-tick` | 8192 | Blocks placed per tick |
| `limits.undo-steps` | 25 | Undoable operations kept per player |
| `limits.max-chunks` | 1024 | Chunks an operation may read |
| `tools.reach` | 160 | Reach of the wand and brushes, in blocks |
| `tools.max-brush-size` | 64 | Largest brush radius |
| `tools.stroke-ticks` | 30 | Brush strokes closer than this are undone together |
| `wand.material` | WOODEN_AXE | Item used as the selection wand |
| `axiom.enabled` | true | Axiom support, if AxiomPaper is installed |
| `axiom.permission` | axiom.default | Axiom permission granted while the player may build |

### Permissions

| Permission | Default | Meaning |
|------------|---------|---------|
| `trowel.use` | op | Every `//` command, `/trowel`, the wand and brushes |
| `trowel.library.admin` | op | Overwrite or delete anyone's schematics |

Where a host plugin governs a world, it decides on top of this permission (see below).

### Data

`plugins/Trowel/` holds `palettes/`, `patterns/`, `masks/` (one file per player) and
`schematics/` (the shared library). When Trowel first starts next to a Celeste install that still
has the old embedded data (`truelle.yml`, `truelle-palettes/`...), it copies it over once.

---

## Contents

1. [Getting started](#1-getting-started)
2. [The selection](#2-the-selection)
3. [Patterns, palettes and masks](#3-patterns-palettes-and-masks)
4. [Noises](#4-noises)
5. [Fill, shapes, lines](#5-fill-shapes-lines)
6. [Splines](#6-splines)
7. [Expressions](#7-expressions)
8. [Terrain](#8-terrain)
9. [Sculpt and deform](#9-sculpt-and-deform)
10. [Textures](#10-textures)
11. [Decor](#11-decor)
12. [Arceon tools](#12-arceon-tools)
13. [Clipboard and moving](#13-clipboard-and-moving)
14. [Brushes](#14-brushes)
15. [History, progress, limits](#15-history-progress-limits)
16. [Recipes](#16-recipes)
17. [Cheat sheet](#17-cheat-sheet)

---

## 1. Getting started

- **G key** (quick actions) or `//menu`: every operation in windows, by category.
- `//help`: every command, clickable. `/trowel <action>` works too (`/trowel set stone`).
- **Completion** (Tab) suggests everything: blocks and their states, palettes, noises, shapes, flags.

First steps:

```
//wand                      the wand
(left click, right click)   both corners of the selection
//set stone                 fills the selection
//undo                      goes back
```

---

## 2. The selection

**The wand** (`//wand`) works from afar: a click in the air aims at the block you look at.

| Wand gesture | Effect |
|--------------|--------|
| Left click / right click | First / second corner |
| Sneak + right click | Expands the selection to the aimed block |
| Sneak + left click | Adds a spline point |

| Command | Effect |
|---------|--------|
| `//pos1` `//pos2` | Corner at your feet |
| `//hpos1` `//hpos2` | Corner on the aimed block |
| `//sel` `//size` | Clear, size |
| `//expand <n> [direction]`, `//expand vert` | Pushes a face; full height |
| `//contract <n> [direction]`, `//shift <n> [direction]` | Pulls in a face; shifts the selection |
| `//outset <n>`, `//inset <n>` | Grows, shrinks on every face |
| `//next [direction] [gap]` | Shifts the selection by its own size |
| `//selhere [pos1\|pos2\|center]` | Brings the selection to your feet |
| `//enc <mask>` | Shrinks the selection around what the mask selects |
| `//selnear <radius> <mask>` | Selects what the mask selects around you |
| `//count <mask>`, `//distr` | Count, block distribution |

The **direction** is `me` (where you look, the default), `up`, `down`, `north`, `south`, `east`, `west`.

---

## 3. Patterns, palettes and masks

### Patterns: what gets placed

| Syntax | Effect |
|--------|--------|
| `stone`, `oak_slab[type=top]` | A block, with its state |
| `60%stone,40%andesite` | A weighted random draw |
| `251:8` | A numeric block id from before 1.13, as in ezEdits (here light gray concrete) |
| `checkpoint` | A host marker by name (when the host plugin provides markers): it becomes a real marker |
| `#hand`, `#hotbar`, `#aim` | The held block, your hotbar (weighted by amounts), the aimed block |
| `##magma` | A ready-made palette, at random |

### Palettes: blocks in an order

A palette is an **ordered** list. A noise, a gradient or an expression picks a block by its
place: the start for 0, the end for 1.

| Syntax | Effect |
|--------|--------|
| `stone,andesite,##grayscale` | One after the other |
| `-##magma` | Reversed |
| `##grayscale(3:8)` | From the 3rd to the 8th block |
| `gold_block*10` | Repeated |
| `-[##magma,gold_block]` | A group treated as one piece |

Ready-made palettes: `//palette list`. Among them: grayscale, graywarm, graycold, stone,
deepslate, magma, lava, fire, glowblue, glowpurple, gloworange, glowgreen, ice, ocean, blue, red,
green, moss, grass, dirt, sand, redsand, badlands, brown, bark, wood, cherry, nether, warped, end,
copper, gold, sunset, rainbow, snow, crystal, glass, bluestained, mud, basalt, marble.

| Command | Effect |
|---------|--------|
| `//palette show <palette>` | Lists its blocks |
| `//palette edit [name]` | Mouse editor: click blocks in your inventory, move slots to reorder, a stack's amount = its repetitions, Shift + click removes, then Save |
| `//palette sort <palette>` | Sorts it from darkest to lightest |
| `//palette place <palette> [direction]` | Places it in a row on the aimed block |
| `//palette swap <from> <to>` | In the selection, each block becomes the one of the same rank in the other |
| `//palette save <name> <palette>` | Saves it for you: then `##name` |
| `//palette delete <name>` | Deletes it |

### Palette patterns

| Pattern | Effect |
|---------|--------|
| `#noise[palette][noise][scale][seed]` | A noise walks the palette |
| `#cracks[palette][scale]` (and every other noise) | Shortcut: `#marble[##marble][12]` |
| `#local[palette][noise]` | The noise follows the shape (spline) instead of the world |
| `#expr[palette][expression]` | The expression (0 to 1) picks the block; negative, nothing is placed |
| `#expri[palette][expression]` | Same, but the value is the block number |
| `#gradient[palette][axis][noise][blend]` | Gradient: `y`, `x`, `z`, `-y`, `radial`, `sphere`, a vector `1,1,0`, or in a spline `t`, `u`, `v`, `r`, `a` |
| `#vgradient[palette][x,y,z][distance]` | Gradient from you, along a vector |
| `#rgradient[palette][distance]` | Gradient around you |
| `#random[palette]`, `#stripes[palette][axis][width]` | Random; in stripes |
| `#clipboard[dx,dy,dz]` | The clipboard repeated as tiling |
| `#color[#ff8800][n]` | The block closest to a color (`red`, `255,136,0`...) |

The older forms still work: `#noise:ridged:8:a,b`, `#voronoi:6:a,b`, `#gradient:a,b`,
`#palette:stone`.

### Named patterns and masks

| Command | Effect |
|---------|--------|
| `//pattern save <name> <pattern>` | Saves a pattern: then `@name` wherever a pattern is asked |
| `//mask save <name> <mask>` | Saves a mask: then `@name` wherever a mask is asked |
| `//pattern list`, `//mask list`, `show <name>`, `delete <name>` | View, type, delete them |

```
//pattern save walls 50%stone_bricks,30%cracked_stone_bricks,20%mossy_stone_bricks
//mask save ground #surface&!water
//set @walls            //brush size 4 then pattern @walls, mask @ground
```

### Masks: what may be touched

Several terms separated by spaces or `&` must all be true. `!` negates.

| Mask | True for |
|------|----------|
| `stone,dirt`, `!air` | These blocks; everything but air |
| `#existing`, `#air`, `#solid` | Not air; air; solid blocks |
| `#surface`, `#floor`, `#ceiling`, `#wall`, `#exposed` | Top, bottom, sides, open to the air |
| `#fullblock`, `#lightsource`, `#attached` | Full blocks, light sources, attached to something |
| `#marker`, `#marker:checkpoint` | Real host markers (not blocks that merely have their material) |
| `>grass_block`, `<stone` | Placed on; under |
| `~water@2` | Within 2 blocks of water |
| `#near[mask][3]`, `#above[mask][d]`, `#below[mask][d]`, `#prox[mask][d]` | At a distance from another mask |
| `#y:60:80`, `#slope[0][40]` (or `#angle:0:40`) | Height; slope of the relief in degrees |
| `#noise[noise][threshold]`, `#cracks[8][50]` | Where the noise exceeds the threshold (or covers 50%) |
| `#palette[palette]`, `#color[red][30]` | The blocks of a palette; of a color |
| `#ygradient[60][90]`, `#ambient[4]`, `%30`, `#random[30]` | Random height gradient; surrounded by air; random |
| `=x*x+z*z<100` | An expression (it takes the whole end of the mask) |

`//gmask <mask>` applies a mask to every operation; `//gmask` alone removes it.

---

## 4. Noises

### Trowel noises

`fractal`, `smooth`, `ridged`, `billow`, `warp` (swirls), `terrace`, `marble`, `strata`, `cells`,
`distance`, `cracks`, `rings`, `waves`, `columns` (basalt), `drips`, `spiral`, `checker`,
`hexagons`, `dither`, `white` (random), `turbulence`, `electric` (lightning).

### Tuned noises

Write `type(setting:value,...)`, as in ezEdits:

```
cellular(f:0.2,cr:edge,cj:0.8)
perlin(f:0.05,ft:ridged,fo:5,wa:12)
gabor(go:45,gf:3)
```

Types: `perlin`, `value`, `valuecubic`, `white`, `cellular`, `gabor`, and each noise above.

| Setting | Meaning |
|---------|---------|
| `f`, `sc`, `s` | Frequency (0.1 = patches of 10 blocks), scale in blocks, seed (-1 = random) |
| `x`, `y`, `z` | Stretch an axis (`y:0.1` lengthens ten times vertically) |
| `ft`, `fo`, `fl`, `fg` | Fractal (none, fbm, ridged, billow, pingpong), octaves, lacunarity, gain |
| `cj`, `cd`, `cr` | Cells: disorder, distance (e, sq, man, cheb, hybrid), return (cell, 1, 2, add, sub, mul, div, edge) |
| `gf`, `gr`, `gn`, `go`, `gj` | Gabor: stripes, size, count, orientation, disorder |
| `wa`, `wf`, `wo` | Noise warp: amplitude in blocks, frequency, octaves |
| `i`, `l`, `u`, `p`, `c`, `st` | Invert, lower and upper bounds, power, contrast, steps |

---

## 5. Fill, shapes, lines

| Command | Effect |
|---------|--------|
| `//set <pattern>` | Fills the selection |
| `//replace [mask] <pattern>` | Replaces what the mask selects |
| `//walls`, `//outline`, `//center <pattern>` | Walls, six faces, center |
| `//overlay <pattern> [thickness]` | Places on top of the relief |
| `//hollow [thickness] [pattern]` | Hollows, keeping a shell |
| `//naturalize`, `//smooth [passes]` | Grass-dirt-stone; smooths the relief |
| `//typereplace oak spruce` | Changes wood type keeping the shapes |
| `//fixconnect` | Redoes the connections of fences, panes, walls |
| `//sphere`, `//hsphere <pattern> <radius>[,ry,rz]` | Ball at your feet, solid or hollow |
| `//cyl`, `//hcyl <pattern> <radius>[,rz] [height]` | Cylinder |
| `//pyramid`, `//hpyramid <pattern> <size>` | Pyramid |
| `//cone`, `//hcone <pattern> <radius> <height>` | Cone |
| `//line <pattern> [thickness]` | Line from corner 1 to corner 2 |
| `//rope <pattern> [sag] [thickness]` | Hanging rope |
| `//arch <pattern> [height] [width] [thickness]` | Arch bridge |

---

## 6. Splines

The spline is the most powerful tool: a **shape swept along points**.

**1. Place the points**: `//point` on the aimed block (or sneak + left click with the wand).
Without points, the spline goes from corner 1 to corner 2.

| Command | Effect |
|---------|--------|
| `//point` / `//point here` | Adds the aimed block / your feet |
| `//point undo`, `//point clear`, `//point list` | Removes the last, clears all, lists |
| `//point sel`, `//point reverse`, `//point insert <n>` | Both corners become the points; reverse; insert |
| `//catenary <points> [sag] [up]` | A catenary (or an arch with `up`) between the first and last point |

**2. Place the spline**:

```
//spline [shape] <pattern> [radii] [flags]
```

```
//spline stone 3                      a tube of radius 3
//spline star(S:6,D:0.4) quartz_block 5
//spline chain iron_block 4
//spline helix(B:4) ##glowblue 6 -e round
```

### Radii

| Syntax | Effect |
|--------|--------|
| `5` | 5 everywhere |
| `1,12` | From 1 at the start to 12 at the end |
| `1,12,1` | 12 in the middle |
| `1,0.2:12,1` | 12 reached at 20% of the way |

### Flags

| Flag | Effect |
|------|--------|
| `-t <degrees>` | Twist (degrees per diameter travelled) |
| `-r <a>` or `-r <a,b>` | Fixed roll, or from start to end |
| `-s <n>` | Stretches (above 1) or squashes the shape along the path |
| `-e flat\|soft\|round\|spike\|cube` | The caps |
| `-n consistent\|horizontal\|upright` | Follows the bends, stays flat, stays upright |
| `-q fast\|balanced\|high\|exact` | Quality |
| `-p tension:bias:continuity` | The curve between the points (-1 to 1) |
| `-h`, `-c`, `-m <mask>` | Hollow, closed loop, what it may replace |
| `-w <profile>` | Shaping blocks on the surface, like ezEdits smoothblocks: `Slabs`, `SlabsAndStairs`, `SlabsAndStairs2D`, `Panes`, `Layers`. Options: `Slabs(Slab:acacia,Coverage:0.3)` |

### Shapes

Settings go in parentheses, by letter or by name: `star(S:6,D:0.3)`. `//spline help <shape>`
details them.

**2D sections** (the cut): `circle`, `square`, `diamond`, `rounded(R)`, `supercircle(E)`,
`circles(C,F)` (`rope` = twisted by 90), `strands(C,S)`, `pipe(I)`, `halfpipe(I)`, `polygon(S)`,
`triangle`, `rectangle(X1,Y1,X2,Y2)`, `star(S,D)`, `flower(C,D)`, `cross(W)`, `crescent(O,R)`,
`gear(T,D,I)`, `heart`, `ellipse(X,Y)`, `roof(H,D,N)`, `road(T,N)`.

**3D shapes** (what repeats along the path): `beads`, `cubes`, `braids`, `chain`, `fishnet`,
`honeycomb`, `oscillate` (ribs), `rings`, `scales`, `noodles`, `spring`, `helix` (twisted blades),
`stones` (loose stones), `bumps`, `thorns`, `dna` (double helix), `lattice`, `bamboo`.

**Advanced shapes**:

| Shape | Effect |
|-------|--------|
| `//spline noise <palette> [radii] [noise] [depth] [-i expression]` | A noise eats the tube; the palette goes from the bottom to the surface. Same formula as ezEdits; `-i` replaces it (x, y, z, n, d, r, t, and xx, yy, zz the squares) |
| `//spline expr <palette> [radii] <expression>` | Your own shape: x, y the cut (-1 to 1), z along the path |
| `//spline clipboard [radii] [-z]` | The clipboard repeated along the path (stretched with `-z`) |

### Example: a DNA double helix as an expression

In `//spline expr`, **x and y** are the cut (the radius is 1), **z** moves along the path
(1 = one radius travelled), **r** is the distance to the axis. The returned value picks the block
in the palette: `0.08` the 1st of six, `0.25` the 2nd, `(b+0.5)/6` the (b+1)-th; 0, nothing.

```
//palette save dna light_blue_concrete,white_concrete,red_concrete,yellow_concrete,lime_concrete,blue_concrete
//spline expr ##dna 6 k=z*1.05;c=cos(k);v=sin(k);u=x*c+y*v;n=floor(z/0.6);p=floor(fract(sin(n*78.2)*4e4)*4);b=u>0?p+2:p+2+(p%2?-1:1);r*r+0.5625-1.5*abs(u)<0.06?(u>0?0.08:0.25):(fract(z/0.6)<0.3&&abs(x*v-y*c)<0.15&&abs(u)<0.75?(b+0.5)/6:0)
```

| Piece | Role |
|-------|------|
| `k=z*1.05;c=cos(k);v=sin(k)` | The angle of the strands turns as you go: one turn every 6 radii |
| `u=x*c+y*v` | The position along the axis of both strands (positive: side of the 1st) |
| `r*r+0.5625-1.5*abs(u)<0.06` | Distance to the closest strand, placed at 0.75 from the axis, thickness 0.25 |
| `n=floor(z/0.6)` | The rung number: one every 0.6 radii, ten per turn |
| `p=floor(fract(sin(n*78.2)*4e4)*4)` | A fixed random value per rung: the base pair (A-T, T-A, G-C, C-G) |
| `b=u>0?p+2:p+2+(p%2?-1:1)` | The base of each half of the rung, its complement opposite |
| `fract(z/0.6)<0.3&&abs(x*v-y*c)<0.15` | The rung: a thin slice, in the plane of both strands |

To change it: `1.05` (twist), `0.75` and `0.5625` (= 0.75 squared, strand spacing), `0.06`
(thickness squared), `0.6` (rung spacing). The ready-made shape `//spline dna(K,T,R,P,H) <pattern> 6`
does the same without colors per base.

### Textures that follow the shape

```
//spline circle #local[##bark][perlin(y:0.1,f:0.4)] 6     bark along the trunk
//spline circle #gradient[##magma][t] 4                     gradient from start to end
//spline circle #gradient[##ice][r] 5                       from the core to the skin
```

---

## 7. Expressions

The syntax is WorldEdit's. **True means positive.**

- Operators: `+ - * / % ^`, `== != < <= > >=`, `&& || !`, `? :`, `= += -= *= /=`, `++ --`
- Statements: `a; b`, `if (c) { } else { }`, `while (c) { }`, `for (i = 0, 9) { }`, `break`, `return`
- Functions: `//functions` (sin, sqrt, clamp, lerp, smoothstep, fract, mod, noise, cracks,
  perlin, voronoi, sdsphere, sdtorus, smin, random...). `solid(dx,dy,dz)` and `air(dx,dy,dz)`
  read the world around the block.

| Command | Effect |
|---------|--------|
| `//generate <pattern\|palette> <expression>` | Places where the expression is positive. x, y, z go from -1 to 1 in the selection |
| `//generate -h ...` | Hollow |
| `//generate -c ...`, `-o`, `-r` | x, y, z in blocks from the center, from you, or world coordinates |
| `//deform <expression>` | Changes x, y, z: each block takes the one at the computed place |

With a palette, the value (0 to 1) picks the block:

```
//g stone x*x+y*y+z*z<1                              a ball
//g ##magma (y+1)/2                                  gradient from bottom to top
//g stone sdtorus(x,y,z,0.6,0.25)<0                  a ring
//g stone x*x+y*y+z*z<1-0.3*noise(x*3,y*3,z*3)       a rock
//deform y-=0.2*sin(x*5)                             a wave
```

---

## 8. Terrain

| Command | Effect |
|---------|--------|
| `//terrain <relief> [scale] [strength %] [layers]` | The selection becomes terrain: hills, mountains, plains, dunes, mesa, islands, craters, canyons, volcanoes, valleys, warped. Layers: top, under, deep, for example `grass_block\|dirt\|stone` |
| `//heightmap <palette> <noise> [scale] [height %]` | A relief drawn from a noise, painted bottom to top |
| `//noise <pattern\|palette> <noise> <scale> [threshold %] [-a]` | Rocks, islands, clouds where the noise exceeds the threshold |
| `//carve <noise> <scale> [threshold %]` | Carves: caves, holes |
| `//roughen <noise> [scale] [amplitude]` | Makes a relief less smooth |
| `//scatter <pattern> [density %] [mask]` | Scatters grass and flowers |
| `//flowfield <palette> [lines\|%] [iterations] [velocity] [palette scalar] [noise]` | Flow field, like ezEdits: lines start at random on the relief, follow the direction a noise gives and repaint the surface. Where lines cross, the palette goes one block further per pass (the scalar speeds that up). Defaults: `10%` of the columns, 32 steps, velocity 1, scalar 1, `perlin` at scale 50. Also `//flow` |
| `//flowfield ... -i <inertia> -g <x,y,z> -m <mask>` | Inertia (0 to below 1: lines bend less), a pull added at each step, where lines may start |
| `//flowfield ... -c -f -t` | Follows the curl of the noise (swirls), fills the untouched surface with the first block, lines through the volume in 3D |
| `//snow`, `//thaw`, `//green` `[radius]` | Snow, thaw, grass |

---

## 9. Sculpt and deform

| Command | Effect |
|---------|--------|
| `//smooth3d <radius> [passes] [bias]` | Smooths volumes in 3D (positive bias: inflates) |
| `//inflate <radius>`, `//deflate <radius>` | Inflates, thins |
| `//surface rockify <radius> [size]` | The skin becomes rocky |
| `//surface fuzzify\|voronoify\|noisify ...` | Fuzz; facets; your own noise. `-c` digs only, `-e` bulges only |
| `//voronoialize [size] [gap]` | Splits into Voronoi cells |
| `//hexagonalize [size] [gap] [angle]` | Hexagonal columns (basalt organs) |
| `//voxelize <size> [gap] [disorder]` | Big cubes |
| `//noisedeform <noise> [strength] [-h\|-v]` | Warps with a noise |
| `//twist <degrees> [axis]` | Twist |
| `//rotatesel <degrees> [axis]` | Rotates by any angle |
| `//taper <factor> [axis]` | Tapers (below 1) or flares |

The work area (selection plus the radius margin) is limited to 8 million blocks.

---

## 10. Textures

```
//texture <type> <mask> <palette> [setting:value...]
```

Repaints a build already placed. **The start of the palette goes to light and bumps, the end to
hollows and shade**: with `##grayscale` (black to white), write `-##grayscale`.

| Type | Effect |
|------|--------|
| `ambient` | Hollows get darker |
| `curvature` | Edges on one side, nooks on the other |
| `sun` | By orientation towards the sun; `shadows:0.5` adds cast shadows |
| `light` | A lamp at your position |
| `axis` | Gradient along an axis (`relative:true`: column by column) |
| `noise`, `cells`, `random` | Noise, cells, random |
| `depth`, `slope` | Distance to air, slope |
| `blend`, `shift` | Soften transitions; shift each block within the palette |

Settings: `radius:3`, `brightness:0.1`, `contrast:0.5`, `dir:0.3,-1,0.2`, `interval:0,180`,
`shadows:0.3`, `axis:y`, `relative:true`, `amount:24`, `noise:perlin(f:0.1)`, `dither:0.6`.

```
//texture ambient #existing -##grayscale radius:3
//texture sun #existing ##sand shadows:0.4
```

---

## 11. Decor

| Command | Effect |
|---------|--------|
| `//vines <mask> <pattern> [%] [min] [max]` | Hanging vines |
| `//moss <pattern> [amount %] [smoothing]` | Moss patches |
| `//fill <pattern> <radius> [depth]` | Fills the hole you stand in |
| `//replacenear`, `//removenear`, `//removeabove`, `//removebelow` | Around you, no selection needed |

---

## 12. Arceon tools

Roof, road, river and dashes follow the spline points.

| Command | Effect |
|---------|--------|
| `//roof <pattern> <width> [height]` | Gable roof. `-n` irregular, `-d` goes down, `-b` hipped ends, `-h` hollow, `-p` straight |
| `//road <pattern> <width> [thickness]` | A road that stays flat. `-n`, `-d` down to the ground, `-p` |
| `//river <pattern> <start depth> <end depth> [slope]` | Carves a bed and fills it (`water`, `snow_block`...) |
| `//dashes <pattern> <dash> <gap> [width] [-d]` | Dotted line |
| `//smear <distance> [direction] [-r]` | Stretches the selection like a trail |
| `//revolve <copies> [start] [end] [height]` | Copies in a crown or a spiral around you |
| `//resize <scale> [-a]` | Enlarges or shrinks the selection |
| `//colorreplace <color> <color> [-b] [-g]` | Changes a color (wool, concrete, terracotta, glass...) |
| `//shadow <pattern>` | Paints the shadow the build casts, seen from you |
| `//smoothsnow [smoothness] [mask] [pattern] [thickness]` | Smooth layered snow |
| `//ocean <level> [pattern]` | Fills the air up to the level |
| `//spiralstairs <pattern> <radius> <height> [turns] [core]` | Spiral staircase |
| `//text <pattern> <size> <text>`, `//font [font]` | Writes in blocks on the aimed block |
| `//loft frame`, `//loft point`, `//loft set <pattern>` | Surface stretched between frames of points (hulls, vaults). `-o` outlines, `-c` closes, `-d` goes down, `-p` faceted |

---

## 13. Clipboard and moving

| Command | Effect |
|---------|--------|
| `//copy`, `//cut` | Copies around your feet, markers included |
| `//paste [-a] [-o] [-s]` | Pastes at your feet; `-a` without air, `-o` at the original place, `-s` selects |
| `//rotate <90\|180\|270>`, `//flip [direction]` | Rotates, flips (marker settings too) |
| `//move <n> [direction]` | Moves the selection and its content |
| `//stack <count> [direction] [-a]` | Repeats the selection |

### The schematic library

Clipboards saved under a name, **shared between players**, markers and settings included: it is
also how markers travel between areas, even days later. Each one has a **thumbnail**, a colored
top view, shown on hover.

| Command | Effect |
|---------|--------|
| `//schem save <name> [-p] [-c]` | Saves the current selection (`-c`: the clipboard instead, `-p`: private) |
| `//schem load <name>` | Loads it back into the clipboard, then `//paste` |
| `//schem list [search\|page]` | The library, click to load |
| `//schem info <name>`, `//schem delete <name>` | Thumbnail and details; delete (author only) |

The **Library** window (G key) shows schematics with their thumbnail as a tooltip.

---

## 14. Brushes

A brush is an item that keeps its settings. `//brushes` (or the Brushes window) to pick one.

| Gesture | Effect |
|---------|--------|
| Right click | Paint |
| Sneak + right click | Paint on the aimed face |
| Left click | Open its settings (radius, pattern, mask, chance, falloff...) |

| Brush | Effect |
|-------|--------|
| Ball, Cylinder, Cube, Disc, Ring | Fills the shape |
| Paint, Splatter, Bucket, Fracture | Recolors the surface, in patches, by area, the edges |
| Overlay, Underlay, Scatter | The top layers, those just below, grass and flowers |
| Raise, Lower, Flatten, Smooth | Relief (Flatten: fill, cut or both) |
| Erode, Blend | Eats away or fills; each block takes its neighbours' material |
| Blob, Boulder, Spike | Organic rock, faceted rock, spike coming out of a wall |
| Drain, Fill down, Shell | Removes water; fills under blocks; hollows |
| Snow pile, Extrude, Stamp | Snow pile; extends a face; places the clipboard |
| Ivy | Vines (or lichen, leaves) starting from walls and hanging under overhangs |
| Stalactites | Under ceilings, and more rarely stalagmites; in `pointed_dripstone`, the thickness follows the real game |
| Cracks | Cracks winding over the surface, carved (`air`) or repainted, to the chosen depth |
| Scree | Stones rolling down slopes and piling up at the bottom |
| Loft | Right click adds the aimed block to the loft frame, sneak opens a new frame; left click to stretch the surface |

`//brush <type> [radius] [pattern]` gives a brush; `//brush size|mask|pattern|type <value>` tunes
the one in hand. A gradient in a brush: pattern `#gradient[##magma][y]`.

---

## 15. History, progress, limits

| Command | Effect |
|---------|--------|
| `//undo [n]`, `//redo [n]` | Undoes, redoes (marker settings included) |
| `//history` | The latest operations: [up to here] or [only this one], on click |
| `//undo only <n>` | Undoes only the n-th; blocks touched since then stay as they are |
| `//undo sel [n]` | Undoes the last n operations, but only inside the selection |
| `//undo brush` | Undoes the last gesture of the brush in hand, even if others followed |
| `//cancel` | Stops the running operation |
| `//markers [protect\|edit]` | Protects (default) or not the markers |
| `//noclip [on\|off]` | Walk and fly through blocks in creative: spectator while a block is in the way, creative again once out |
| `//coedit [show\|share] [on\|off]` | See the selection of other builders in the same world while they hold a Trowel tool (off by default), show yours (on by default) |
| `//axiom` | State of the Axiom support for you |

- **Computing runs in the background**, only placing runs on the server thread. A **subtitle**
  shows the progress: stage, percentage, blocks found, time.
- `//cancel` stops computing without placing anything, or the placing in progress (what is
  already placed can still be undone with `//undo`).
- If computing **fails** (area too large, memory, invalid expression), the subtitle shows it, the
  reason is in chat, nothing is placed, and you can start again right away.
- Nothing is placed **outside the buildable area** set by the host plugin; the number of dropped
  blocks is reported.
- An operation has a **block limit** (`limits.max-blocks`, 50,000 by default).
- **Markers** are never overwritten, unless after `//markers edit`.

### Axiom

If the server runs AxiomPaper, the Axiom mod works too, but only where Trowel does:

- the Axiom permission (`axiom.permission`) is only granted while the player may build, and
  removed as soon as they leave;
- Trowel hooks into Axiom's protection hook, the one PlotSquared and WorldGuard use: Axiom only
  places and breaks inside the buildable area. Its world, game mode, teleport, time and entity
  actions are refused elsewhere.

---

## 16. Recipes

```
Bumpy rock tube          //spline noise ##grayscale 6 perlin(f:1.5,fo:3) 0.6
Cracked lava tube        //spline noise -##magma 6 cellular(cr:edge,f:1.2) 0.5
Tree trunk               //spline circle #local[##bark][perlin(y:0.1,f:0.4)] 6,3 -e round
Glass spring             //spline spring white_stained_glass 6
DNA with base pairs      see "Example: a DNA double helix" (section 6)
Twisted blades           //spline helix(B:4) ##glowblue 6
Loose stones             //spline stones(G:0.3,K:60) green_terracotta 6
Chain                    //spline chain iron_block 3
Braided rope             //spline rope ##brown 2
Suspension bridge        //catenary 12 4   then   //spline road(T:0.3) spruce_planks 2 -n horizontal
Roof                     //roof #noise[##deepslate][cells] 9 5 -b
River                    //river water 2 4 1.5 -n
Rock                     //g stone x*x+y*y+z*z<1-0.3*noise(x*3,y*3,z*3)
Textured cliff           //surface rockify 2 8   then   //texture ambient #existing -##stone
Basalt columns           //hexagonalize 4 0.4
Sign                     //font Serif   then   //text stone 16 Welcome
```

---

## 17. Cheat sheet

```
Selection    //wand  //pos1 //pos2  //expand  //contract  //next  //enc
Fill         //set  //replace  //walls  //overlay  //hollow
Shapes       //sphere  //cyl  //cone  //pyramid  //line  //rope  //arch
Splines      //point  //spline [shape] <pattern> [radii] [-t -r -s -e -n -q -p -h -c]
Expressions  //generate <pattern> <expression>  //deform <expression>  //functions
Terrain      //terrain  //heightmap  //noise  //carve  //roughen  //scatter  //flowfield
Sculpt       //smooth3d  //inflate  //surface  //voronoialize  //hexagonalize  //twist
Textures     //texture <type> <mask> <palette>
Arceon       //roof  //road  //river  //revolve  //resize  //shadow  //text  //loft
Brushes      //brushes  //brush <type>
History      //undo [n|only n|sel|brush]  //redo  //history  //cancel
Library      //schem save|load|list  //pattern save  //mask save  (@name)  //palette edit
Help         //help  //spline help  //texture help  //palette list  G key
```

---

## For plugin developers

Trowel publishes a `TrowelApi` service. Depend on it in `paper-plugin.yml`:

```yaml
dependencies:
  server:
    Trowel:
      load: BEFORE
      required: true
      join-classpath: true
```

and in Maven (after `mvn install` of Trowel):

```xml
<dependency>
    <groupId>com.stackmc</groupId>
    <artifactId>Trowel</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

### Hosts: who builds where

A host decides, for the worlds it handles, who may use Trowel, inside which area, and how its
marker blocks are stored. Worlds no host handles use the default rules: the `trowel.use`
permission, no area limit, no markers.

```java
TrowelApi trowel = TrowelApi.get();
trowel.registerHost(this, new TrowelHost() {
    @Override
    public boolean handles(World world) {
        return world.getName().startsWith("plot_");
    }

    @Override
    public String denyEdit(Player player) {
        return isOwner(player) ? null : "This plot is not yours.";
    }

    @Override
    public Box bounds(World world) {
        return new Box(0, 0, 0, 127, 255, 127);
    }

    @Override
    public MarkerSupport markers() {
        return MarkerSupport.NONE;
    }

    @Override
    public void changed(World world, boolean markerParamsChanged) {
        markDirty(world);
    }
});
```

- `denyEdit` returns the reason shown to the player, or `null` to allow. It is asked before every
  operation, by the display and by the Axiom bridge.
- `bounds` crops every selection, operation, brush stroke and Axiom edit (`null`: whole world).
- `markers` lets Trowel move, copy, rotate and protect blocks that carry settings stored outside
  the world. Positions are packed with `Keys.pack(x, y, z)`. Methods without a world are called
  off the main thread.
- `changed` is called after Trowel placed blocks, to save or refresh.
- Hosts are asked in registration order; the first whose `handles` returns `true` wins. Call
  `unregisterHost(plugin)` when disabling.

### Other calls

| Method | Use |
|--------|-----|
| `wand()` | A fresh selection wand |
| `isTool(ItemStack)` | Whether an item is the wand or a brush |
| `selection(Player, World)` | The player's selection, cropped to the buildable area, or `null` |
| `clearSelection(Player)` | Clears it |
| `openMenu(Player)`, `openBrushes(Player)` | The G-key home window; the brush picker |
| `reload()` | Reads `config.yml` again |

Trowel items carry the persistent data key `trowel:tool` (and `trowel:brush` for brush settings).
