import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Toggle } from './Toggle.vue'

export const toggleVariants = cva(
  'inline-flex items-center justify-center gap-2 rounded-md text-sm font-medium hover:bg-muted hover:text-muted-foreground disabled:pointer-events-none disabled:opacity-50 data-[state=on]:bg-accent data-[state=on]:text-accent-foreground [&_svg]:pointer-events-none [&_svg:not([class*=\'size-\'])]:size-4 [&_svg]:shrink-0 focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3 outline-none transition-[color,box-shadow] aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive whitespace-nowrap',
  {
    variants: {
      variant: {
        'default': 'bg-transparent',
        'outline':
          'border border-input bg-transparent shadow-xs hover:bg-accent hover:text-accent-foreground',
        /** 谛听补充：分段控件选中态为实心金色（旧定稿 .mode-btn.on） */
        'gold':
          'text-muted-foreground hover:bg-surface-2 hover:text-foreground data-[state=on]:bg-primary data-[state=on]:text-primary-foreground data-[state=on]:font-semibold',
        /** 谛听补充：时段切换选中态为金色描边（旧定稿 .range button.on） */
        'gold-outline':
          'border border-border bg-surface-2 text-foreground-soft hover:text-foreground data-[state=on]:border-gold data-[state=on]:bg-surface-2 data-[state=on]:text-gold',
      },
      size: {
        default: 'h-9 px-2 min-w-9',
        sm: 'h-8 px-1.5 min-w-8',
        lg: 'h-10 px-2.5 min-w-10',
        /** 谛听补充：旧定稿的分段按钮（.mode-btn 32px / .range button 约 30px） */
        compact: 'h-8 px-3 min-w-8 text-xs',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)

export type ToggleVariants = VariantProps<typeof toggleVariants>
