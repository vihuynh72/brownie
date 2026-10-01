/**
 * The interface's icon set, as plain path data so it can be drawn inline
 * by AppIcon.vue: no sprite request, no icon font, and no third-party
 * dependency for a dozen small shapes. Each icon is a 24x24 outline drawn
 * with strokes, so it takes the color and weight of the text beside it.
 */
export type IconName =
  | 'home'
  | 'chat'
  | 'plus'
  | 'trash'
  | 'restore'
  | 'shield'
  | 'link'
  | 'upload'
  | 'document'
  | 'sign-out'
  | 'sign-in'
  | 'panel-collapse'
  | 'panel-expand'
  | 'menu'
  | 'undo'
  | 'history'
  | 'send'
  | 'close'
  | 'download'
  | 'share'
  | 'more'
  | 'account'

export const ICON_PATHS: Record<IconName, readonly string[]> = {
  home: ['M3 10.6 12 3l9 7.6', 'M5.6 9.4V20h12.8V9.4', 'M10 20v-5h4v5'],
  chat: ['M8.4 19.2A8.5 8.5 0 1 0 4.3 15.1L3.5 20.5z'],
  plus: ['M12 5.5v13', 'M5.5 12h13'],
  trash: [
    'M3.5 6.5h17',
    'M9 6.5v-2A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5v2',
    'M5.5 6.5l1 13A1.5 1.5 0 0 0 8 21h8a1.5 1.5 0 0 0 1.5-1.5l1-13',
    'M10 10.5v6.5',
    'M14 10.5v6.5',
  ],
  restore: ['M4.5 9.5h9a5.5 5.5 0 0 1 0 11H8', 'M8.5 5.5 4.5 9.5l4 4'],
  shield: ['M12 3.5 5 6v5.5c0 4.2 2.9 7.6 7 9 4.1-1.4 7-4.8 7-9V6z', 'M9.2 12.2l2 2 3.8-4'],
  link: ['M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1.5 1.5', 'M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1.5-1.5'],
  upload: ['M12 15.5V4', 'M8 8l4-4 4 4', 'M4.5 14.5V18a2.5 2.5 0 0 0 2.5 2.5h10a2.5 2.5 0 0 0 2.5-2.5v-3.5'],
  document: ['M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z', 'M13.5 3v5.5H19'],
  // Leaving: the arrow points out of the frame. Arriving: it points into it.
  'sign-out': ['M9.5 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3.5', 'M14 16l4-4-4-4', 'M18 12H8'],
  'sign-in': ['M14.5 4H18a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3.5', 'M10 16l4-4-4-4', 'M14 12H4'],
  'panel-collapse': [
    'M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5z',
    'M9.5 3.5v17',
    'M16 9l-3 3 3 3',
  ],
  'panel-expand': [
    'M5 3.5h14a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5z',
    'M9.5 3.5v17',
    'M13 9l3 3-3 3',
  ],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  undo: ['M9 14 4.5 9.5 9 5', 'M4.5 9.5h10a5 5 0 0 1 0 10H11'],
  history: ['M4 12a8 8 0 1 0 2.3-5.6', 'M4 4.5V8h3.5', 'M12 8v4.2l2.8 1.8'],
  send: ['M12 19V5.5', 'M6.5 11 12 5.5l5.5 5.5'],
  close: ['M6.5 6.5l11 11', 'M17.5 6.5l-11 11'],
  download: ['M12 4v11.5', 'M7.5 11 12 15.5 16.5 11', 'M4.5 19.5h15'],
  share: ['M12 15V3.5', 'M8 7.5l4-4 4 4', 'M7.5 11H6a1.5 1.5 0 0 0-1.5 1.5v6A1.5 1.5 0 0 0 6 20h12a1.5 1.5 0 0 0 1.5-1.5v-6A1.5 1.5 0 0 0 18 11h-1.5'],
  // Three small rings rather than dots: a stroke-only icon has no fill to draw a dot with.
  more: ['M4.8 12a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0', 'M10.8 12a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0', 'M16.8 12a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0'],
  // The signed-in person, as the plain ring the design gives them in place of a picture.
  account: ['M2 12a10 10 0 1 0 20 0 10 10 0 1 0-20 0'],
}
