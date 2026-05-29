import {
  Search,
  X,
  Eye,
  Loader2,
  MessageCircle,
  ThumbsUp,
  TrendingUp,
  Heart,
  Star,
  Tag,
  Ticket,
  Share2,
  Bookmark,
  Bell,
  User,
  Settings,
  ChevronDown,
  ChevronRight,
  ChevronLeft,
  ChevronUp,
  ArrowRight,
  ArrowLeft,
  Plus,
  Minus,
  Check,
  AlertCircle,
  Info,
  Home,
  ShoppingBag,
  Percent,
  DollarSign,
  Clock,
  Calendar,
  Filter,
  Grid,
  List,
  ExternalLink,
  Copy,
  RefreshCw,
  Trash2,
  Edit,
  Upload,
  Download,
  Link,
  Mail,
  Lock,
  Unlock,
  Globe,
  Package,
  Zap,
  Award,
  Gift,
  FireExtinguisher,
  Flame,
} from 'lucide-react'

const iconMap = {
  search: Search,
  close: X,
  x: X,
  eye: Eye,
  loader: Loader2,
  messageCircle: MessageCircle,
  message: MessageCircle,
  thumbsUp: ThumbsUp,
  trendingUp: TrendingUp,
  trending: TrendingUp,
  heart: Heart,
  star: Star,
  tag: Tag,
  ticket: Ticket,
  share: Share2,
  bookmark: Bookmark,
  bell: Bell,
  user: User,
  settings: Settings,
  chevronDown: ChevronDown,
  chevronRight: ChevronRight,
  chevronLeft: ChevronLeft,
  chevronUp: ChevronUp,
  arrowRight: ArrowRight,
  arrowLeft: ArrowLeft,
  plus: Plus,
  minus: Minus,
  check: Check,
  alertCircle: AlertCircle,
  info: Info,
  home: Home,
  shoppingBag: ShoppingBag,
  percent: Percent,
  dollarSign: DollarSign,
  clock: Clock,
  calendar: Calendar,
  filter: Filter,
  grid: Grid,
  list: List,
  externalLink: ExternalLink,
  copy: Copy,
  refresh: RefreshCw,
  trash: Trash2,
  edit: Edit,
  upload: Upload,
  download: Download,
  link: Link,
  mail: Mail,
  lock: Lock,
  unlock: Unlock,
  globe: Globe,
  package: Package,
  zap: Zap,
  award: Award,
  gift: Gift,
  flame: Flame,
}

const sizeMap = {
  xs: 'w-3 h-3',
  sm: 'w-4 h-4',
  md: 'w-5 h-5',
  lg: 'w-6 h-6',
  xl: 'w-8 h-8',
}

const colorMap = {
  muted: 'text-gray-400',
  white: 'text-white',
  primary: 'text-primary-600',
  danger: 'text-red-500',
  success: 'text-green-500',
  warning: 'text-amber-500',
  current: 'text-current',
}

export function Icon({ name, size = 'md', color, className = '', ...props }) {
  const LucideIcon = iconMap[name]

  if (!LucideIcon) {
    if (import.meta.env.DEV) {
      console.warn(`[Icon] Unknown icon name: "${name}"`)
    }
    return null
  }

  const sizeClass = sizeMap[size] || sizeMap.md
  const colorClass = color ? (colorMap[color] || '') : ''

  return (
    <LucideIcon
      className={`${sizeClass} ${colorClass} ${className}`.trim()}
      {...props}
    />
  )
}

export default Icon
