import type { LucideIcon } from 'lucide-react'
import {
  Barcode,
  ClipboardList,
  Inbox,
  LayoutDashboard,
  Package,
  PauseCircle,
  ShoppingBag,
  Store,
  Upload,
  Warehouse,
} from 'lucide-react'
import type { Me } from '../auth/AuthContext'

export type NavItem = {
  href: string
  labelTh: string
  /** English accessible name for tests and screen readers */
  ariaLabel: string
  icon: LucideIcon
  badgeKey?: 'orders' | 'holds'
  roles?: Me['role'][]
}

export type NavSection = {
  title: string
  items: NavItem[]
}

export function buildNavSections(me: Me): NavSection[] {
  const opsOnly = me.role === 'OWNER' || me.role === 'ADMIN'
  const sections: NavSection[] = [
    {
      title: 'ภาพรวม',
      items: [
        {
          href: '#/',
          labelTh: 'แดชบอร์ด',
          ariaLabel: 'Dashboard',
          icon: LayoutDashboard,
        },
      ],
    },
    {
      title: 'ขาย',
      items: [
        {
          href: '#/orders',
          labelTh: 'ออเดอร์',
          ariaLabel: 'Orders',
          icon: ShoppingBag,
          badgeKey: 'orders',
        },
        {
          href: '#/orders/holds',
          labelTh: 'คิวออเดอร์ค้าง',
          ariaLabel: 'Hold queue',
          icon: PauseCircle,
          badgeKey: 'holds',
        },
        {
          href: '#/channel/listings',
          labelTh: 'Listings',
          ariaLabel: 'Listings',
          icon: Store,
        },
      ],
    },
    {
      title: 'แคตตาล็อก',
      items: [
        {
          href: '#/catalog/products',
          labelTh: 'สินค้า (Products)',
          ariaLabel: 'Products',
          icon: Package,
        },
        { href: '#/catalog/skus', labelTh: 'SKUs', ariaLabel: 'SKUs', icon: Barcode },
        {
          href: '#/catalog/import',
          labelTh: 'นำเข้าข้อมูล (Import)',
          ariaLabel: 'Import',
          icon: Upload,
        },
      ],
    },
    {
      title: 'คลังสินค้า',
      items: [
        {
          href: '#/warehouses',
          labelTh: 'คลัง (Warehouses)',
          ariaLabel: 'Warehouses',
          icon: Warehouse,
        },
        {
          href: '#/stock/documents',
          labelTh: 'เอกสารสต็อก',
          ariaLabel: 'Stock documents',
          icon: ClipboardList,
        },
      ],
    },
    {
      title: 'ระบบ',
      items: opsOnly
        ? [
            {
              href: '#/admin/outbox',
              labelTh: 'Dead outbox',
              ariaLabel: 'Dead outbox',
              icon: Inbox,
            },
          ]
        : [],
    },
  ]
  return sections.filter((section) => section.items.length > 0)
}

export function breadcrumbParts(route: string): string[] {
  const base = route.split('?')[0]
  if (base === '#/' || base === '') return ['แดชบอร์ด']
  if (base.startsWith('#/orders/holds')) return ['คิวออเดอร์ค้าง']
  if (/^#\/orders\/[0-9a-f-]{36}$/.test(base)) return ['ออเดอร์', 'รายละเอียด']
  if (base.startsWith('#/orders')) return ['ออเดอร์']
  if (base.startsWith('#/channel/listings')) return ['Listings']
  if (base.startsWith('#/catalog/products')) return ['สินค้า']
  if (base.startsWith('#/catalog/skus')) return ['SKUs']
  if (base.startsWith('#/catalog/import')) return ['นำเข้าข้อมูล']
  if (base.startsWith('#/warehouses')) return ['คลัง']
  if (base.startsWith('#/stock/documents')) return ['เอกสารสต็อก']
  if (base.startsWith('#/admin/outbox')) return ['Dead outbox']
  return ['OMS']
}

export function breadcrumbForRoute(route: string): string {
  const parts = breadcrumbParts(route)
  return parts[parts.length - 1] ?? 'OMS'
}

