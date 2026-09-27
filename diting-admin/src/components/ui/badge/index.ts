import type { VariantProps } from 'class-variance-authority'
import { cva } from 'class-variance-authority'

export { default as Badge } from './Badge.vue'

export const badgeVariants = cva(
  'inline-flex items-center justify-center rounded-full border px-2 py-0.5 text-xs font-medium w-fit whitespace-nowrap shrink-0 [&>svg]:size-3 gap-1 [&>svg]:pointer-events-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-3 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive transition-[color,box-shadow] overflow-hidden',
  {
    variants: {
      variant: {
        default:
          'border-transparent bg-primary text-primary-foreground [a&]:hover:bg-primary/90',
        secondary:
          'border-transparent bg-secondary text-secondary-foreground [a&]:hover:bg-secondary/90',
        destructive:
         'border-transparent bg-destructive text-white [a&]:hover:bg-destructive/90 focus-visible:ring-destructive/20 dark:focus-visible:ring-destructive/40 dark:bg-destructive/60',
        outline:
          'text-foreground [a&]:hover:bg-accent [a&]:hover:text-accent-foreground',
        /** 谛听补充：旧定稿 .chip 的四种语义色（绿/玻珀/红/金） */
        up: 'border-up/30 bg-up/10 text-up',
        warn: 'border-warn/30 bg-warn/10 text-warn',
        down: 'border-down/30 bg-down/10 text-down',
        gold: 'border-gold/35 bg-gold/10 text-gold',
      },
      size: {
        default: '',
        /** 旧定稿 .chip：小字号 + 小圆角 + 紧内边距，用于表格里的状态标记 */
        chip: 'rounded-md px-[7px] py-[2px] text-[10.5px] font-normal',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
)
export type BadgeVariants = VariantProps<typeof badgeVariants>
