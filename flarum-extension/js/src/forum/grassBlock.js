/**
 * A Minecraft grass block as an inline SVG.
 *
 * Drawn instead of shipped as an image on purpose: the extension has no CSS or
 * asset build, and an inline SVG inherits the surrounding line height, scales
 * with the font and cannot 404.
 */
export default function grassBlock(size = 16) {
  const pixel = (x, y, width, height, fill) => m('rect', { x, y, width, height, fill });

  return m(
    'svg',
    {
      viewBox: '0 0 16 16',
      width: size,
      height: size,
      'aria-hidden': 'true',
      focusable: 'false',
      style: 'vertical-align:-2px',
    },
    [
      // Dirt body
      pixel(0, 0, 16, 16, '#8a5a2b'),
      // Grass layer: bright top, darker fringe, two highlights and a shadow
      pixel(0, 0, 16, 4, '#5da33a'),
      pixel(0, 4, 16, 1, '#3f7a25'),
      pixel(1, 1, 3, 2, '#6fbb48'),
      pixel(11, 1, 4, 2, '#4e8f2e'),
      pixel(6, 5, 2, 2, '#4e8f2e'),
      // Dirt speckles, so it reads as a block and not as a flat square
      pixel(2, 7, 2, 2, '#75492a'),
      pixel(7, 9, 3, 2, '#6c4326'),
      pixel(12, 11, 2, 2, '#9a6a3c'),
      pixel(4, 13, 3, 2, '#6c4326'),
      pixel(9, 6, 2, 2, '#9a6a3c'),
    ]
  );
}
