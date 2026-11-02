import type { Booking, BookingStatus } from '../../lib/api'

export interface AdminPage<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AdminCustomer {
  id: string
  name: string
  phoneNumber: string | null
  visits: number
  totalPaid: number
  outstandingDebt: number
}

export interface AdminCustomerDetails {
  customer: AdminCustomer
  bookings: Array<{
    id: string
    serviceName: string
    servicePrice: number
    status: BookingStatus
    createdAt: string
    products: Array<{
      productName: string
      unit: string
      quantity: number
      unitPrice: number
      lineTotal: number
    }>
  }>
  payments: AdminPayment[]
  accounts: Array<{
    id: string
    username: string
    displayName: string
    phoneNumber: string
  }>
}

export interface AdminDashboardTrendPoint {
  date: string
  bookings: number
  collected: number
}

export interface AdminPayment {
  id: string
  bookingId: string
  customerName: string
  amount: number
  method: 'CASH' | 'CARD' | 'TRANSFER' | 'OTHER'
  notes: string | null
  paidAt: string
}

export interface AdminDebt {
  bookingId: string
  customerId: string
  customerName: string
  phoneNumber: string | null
  serviceName: string
  servicePrice: number
  balance: number
}

export interface AdminExpense {
  id: string
  category: string
  description: string
  amount: number
  expenseDate: string
  method: string
  notes: string | null
  createdAt: string
}

export interface AdminReport {
  fromDate: string
  toDate: string
  collectedRevenue: number
  expenses: number
  netProfit: number
  outstandingDebt: number
}

export interface AdminBestSellingProduct {
  productName: string
  unit: string
  quantitySold: number
  salesTotal: number
  bookingCount: number
}

export interface AdminBestSellingService {
  serviceName: string
  bookingCount: number
  serviceRevenue: number
}

export interface AdminBookingSalesReport {
  bookingId: string
  customerName: string
  completedAt: string
  serviceName: string
  servicePrice: number
  products: Array<{
    productName: string
    unit: string
    quantity: number
    unitPrice: number
    lineTotal: number
  }>
  invoiceTotal: number
  amountPaid: number
  balance: number
}

export interface AdminProduct {
  id: string
  name: string
  unit: string
  quantity: number
  lowStockThreshold: number
  purchasePrice: number
  salePrice: number
  imageUrl: string | null
  active: boolean
}

export interface AdminEmployee {
  id: string
  name: string
  phoneNumber: string | null
  jobTitle: string
  baseSalary: number
  commissionPercent: number
  commissionEarned: number
  commissionPaid: number
  active: boolean
}

export interface AdminInventoryMovement {
  id: string
  productId: string
  movementType: 'PURCHASE' | 'CONSUMPTION' | 'SALE' | 'ADJUSTMENT'
  quantity: number
  unitCost: number | null
  notes: string | null
  createdAt: string
}

export interface AdminEmployeePayment {
  id: string
  employeeId: string
  employeeName: string
  amount: number
  paymentType: string
  periodStart: string | null
  periodEnd: string | null
  notes: string | null
  paidAt: string
}

export interface AdminShopProfile {
  phoneNumber: string | null
  address: string | null
  openingHours: string
  updatedAt: string
}

export interface AdminNotification {
  type: string
  message: string
  count: number
}

export type AdminBooking = Booking
