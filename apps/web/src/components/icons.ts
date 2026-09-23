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

export const ICON_PATHS: Record<IconName, readonly string[]> = {
  home: ['M3 10.6 12 3l9 7.6', 'M5.6 9.4V20h12.8V9.4', 'M10 20v-5h4v5'],
  chat: ['M20 14.5a3 3 0 0 1-3 3H8.5L4.5 20.5V6a3 3 0 0 1 3-3h9.5a3 3 0 0 1 3 3z'],
  plus: ['M12 5.5v13', 'M5.5 12h13'],
  trash: ['M4 7h16', 'M10 7V4.8h4V7', 'M6.4 7 7.5 20h9L17.6 7', 'M10.4 10.5v6', 'M13.6 10.5v6'],
  restore: ['M4.5 9.5h9a5.5 5.5 0 0 1 0 11H8', 'M8.5 5.5 4.5 9.5l4 4'],
  shield: ['M12 3.5 5 6v5.5c0 4.2 2.9 7.6 7 9 4.1-1.4 7-4.8 7-9V6z', 'M9.2 12.2l2 2 3.8-4'],
  link: ['M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1.5 1.5', 'M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1.5-1.5'],
  upload: ['M12 15.5V4', 'M8 8l4-4 4 4', 'M4.5 14.5V18a2.5 2.5 0 0 0 2.5 2.5h10a2.5 2.5 0 0 0 2.5-2.5v-3.5'],
  document: ['M13.5 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8.5z', 'M13.5 3v5.5H19'],
  'sign-out': ['M14.5 4H18a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3.5', 'M10 16l4-4-4-4', 'M14 12H4'],
  'sign-in': ['M9.5 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h3.5', 'M14 16l4-4-4-4', 'M18 12H8'],
  'panel-collapse': [
    'M4.5 5h15a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z',
    'M9.5 5v14',
    'M16.5 9.5 14 12l2.5 2.5',
  ],
  'panel-expand': [
    'M4.5 5h15a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z',
    'M9.5 5v14',
    'M14 9.5 16.5 12 14 14.5',
  ],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
}
