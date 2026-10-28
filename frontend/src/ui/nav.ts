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
import { labelTh } from './labels'
import type { Me } from '../auth/AuthContext'

export type NavItem = {
  href: string
  labelTh: string
  /** Thai accessible name for screen readers */
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
          ariaLabel: 'แดชบอร์ด',
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
          ariaLabel: 'ออเดอร์',
          icon: ShoppingBag,
          badgeKey: 'orders',
        },
        {
          href: '#/orders/holds',
          labelTh: 'คิวออเดอร์ค้าง',
          ariaLabel: 'คิวออเดอร์ค้าง',
          icon: PauseCircle,
          badgeKey: 'holds',
        },
        {
          href: '#/channel/stock-drift',
          labelTh: labelTh('STOCK_DRIFT'),
          ariaLabel: labelTh('STOCK_DRIFT'),
          icon: Store,
        },
        {
          href: '#/channel/listings',
          labelTh: 'รายการขาย',
          ariaLabel: 'รายการขาย',
          icon: Store,
        },
      ],
    },
    {
      title: 'แคตตาล็อก',
      items: [
        {
          href: '#/catalog/products',
          labelTh: 'สินค้า',
          ariaLabel: 'สินค้า',
          icon: Package,
        },
        { href: '#/catalog/skus', labelTh: 'รหัสสินค้า', ariaLabel: 'รหัสสินค้า', icon: Barcode },
        {
          href: '#/catalog/import',
          labelTh: 'นำเข้าข้อมูล',
          ariaLabel: 'นำเข้าข้อมูล',
          icon: Upload,
        },
      ],
    },
    {
      title: 'คลังสินค้า',
      items: [
        {
          href: '#/fulfillment/pick-lists',
          labelTh: labelTh('PICK_LIST_TITLE'),
          ariaLabel: labelTh('PICK_LIST_TITLE'),
          icon: ClipboardList,
        },
        {
          href: '#/warehouses',
          labelTh: 'คลังสินค้า',
          ariaLabel: 'คลังสินค้า',
          icon: Warehouse,
        },
        {
          href: '#/stock/documents',
          labelTh: 'เอกสารสต็อก',
          ariaLabel: 'เอกสารสต็อก',
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
              labelTh: 'เหตุการณ์ส่งไม่สำเร็จ',
              ariaLabel: 'เหตุการณ์ส่งไม่สำเร็จ',
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
  if (base === '#/channel/stock-drift') return [labelTh('STOCK_DRIFT')]
  if (base.startsWith('#/channel/listings')) return ['รายการขาย']
  if (base.startsWith('#/catalog/products')) return ['สินค้า']
  if (base.startsWith('#/catalog/skus')) return ['รหัสสินค้า']
  if (base.startsWith('#/catalog/import')) return ['นำเข้าข้อมูล']
  if (base.startsWith('#/warehouses')) return ['คลัง']
  if (base.startsWith('#/fulfillment/pick-lists')) return [labelTh('PICK_LIST_TITLE')]
  if (/^#\/fulfillment\/pack\/[0-9a-f-]{36}$/.test(base)) return [labelTh('PICK_LIST_TITLE'), labelTh('PACK_TITLE')]
  if (base.startsWith('#/stock/documents')) return ['เอกสารสต็อก']
  if (base.startsWith('#/admin/outbox')) return ['เหตุการณ์ส่งไม่สำเร็จ']
  return ['ระบบจัดการออเดอร์']
}

export function breadcrumbForRoute(route: string): string {
  const parts = breadcrumbParts(route)
  return parts[parts.length - 1] ?? 'ระบบจัดการออเดอร์'
}

