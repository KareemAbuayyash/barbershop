import { useEffect, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { BarChart3, BellRing, CircleDollarSign, CreditCard, Eye, History, Plus, Wallet, X } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import { getShopDate } from '../../../lib/shop-time'
import type {
  AdminBestSellingProduct,
  AdminBestSellingService,
  AdminBookingSalesReport,
  AdminCustomer,
  AdminCustomerDetails,
  AdminExpense,
  AdminPayment,
  AdminReport,
} from '../admin-api'

type Section = 'accounts' | 'expenses' | 'reports'
type FinanceLoadResult =
  | { section: 'accounts'; customers: AdminCustomer[]; payments: AdminPayment[] }
  | { section: 'expenses'; expenses: AdminExpense[] }
  | {
      section: 'reports'
      report: AdminReport
      bestSellingServices: AdminBestSellingService[]
      bestSellingProducts: AdminBestSellingProduct[]
      bookingSales: AdminBookingSalesReport[]
    }

const methods = [
  { value: 'CASH', label: 'نقداً' },
  { value: 'CARD', label: 'بطاقة' },
  { value: 'TRANSFER', label: 'تحويل' },
  { value: 'OTHER', label: 'أخرى' },
]

const labels: Record<Section, { eyebrow: string; title: string }> = {
  accounts: { eyebrow: 'أرصدة العملاء والدفعات المحصلة', title: 'الحسابات والديون' },
  expenses: { eyebrow: 'النفقات التشغيلية المسجلة', title: 'المصروفات' },
  reports: { eyebrow: 'ملخص نقدي حسب فترة التحصيل', title: 'الأرباح والتقارير' },
}

function AdminFinancePage({ section }: { section: Section }) {
  const [customers, setCustomers] = useState<AdminCustomer[]>([])
  const [selectedCustomerDetails, setSelectedCustomerDetails] = useState<AdminCustomerDetails | null>(null)
  const [addDebtOpen, setAddDebtOpen] = useState(false)
  const [payments, setPayments] = useState<AdminPayment[]>([])
  const [paymentsOpen, setPaymentsOpen] = useState(false)
  const [expenses, setExpenses] = useState<AdminExpense[]>([])
  const [report, setReport] = useState<AdminReport | null>(null)
  const [bestSellingProducts, setBestSellingProducts] = useState<AdminBestSellingProduct[]>([])
  const [bestSellingServices, setBestSellingServices] = useState<AdminBestSellingService[]>([])
  const [bookingSales, setBookingSales] = useState<AdminBookingSalesReport[]>([])
  const [query, setQuery] = useState('')
  const [fromDate, setFromDate] = useState(getShopDate().slice(0, 7) + '-01')
  const [toDate, setToDate] = useState(getShopDate())
  const [reportMonth, setReportMonth] = useState(getShopDate().slice(0, 7))
  const [busy, setBusy] = useState(false)
  const [reminderBusyId, setReminderBusyId] = useState('')
  const [customerPaymentBusyId, setCustomerPaymentBusyId] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    let active = true
    const params = new URLSearchParams({ fromDate, toDate })
    const fetchData = async (): Promise<FinanceLoadResult> => {
      if (section === 'accounts') {
        const searchParams = new URLSearchParams({ page: '0', size: '100' })
        if (query.trim()) searchParams.set('q', query.trim())
        const [customerPage, paid] = await Promise.all([
          apiRequest<{ content: AdminCustomer[] }>(`/api/admin/customers?${searchParams}`),
          apiRequest<AdminPayment[]>(`/api/admin/payments?${params}`),
        ])
        return { section, customers: customerPage.content, payments: paid }
      } else if (section === 'expenses') {
        return { section, expenses: await apiRequest<AdminExpense[]>(`/api/admin/expenses?${params}`) }
      }
      const [year, month] = reportMonth.split('-').map(Number)
      const monthParams = new URLSearchParams({
        fromDate: `${reportMonth}-01`,
        toDate: new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10),
      })
      const [reportData, bestSellers, topServices, bookingsData] = await Promise.all([
        apiRequest<AdminReport>(`/api/admin/reports?${monthParams}`),
        apiRequest<AdminBestSellingProduct[]>(`/api/admin/reports/products?${monthParams}`),
        apiRequest<AdminBestSellingService[]>(`/api/admin/reports/services?${monthParams}`),
        apiRequest<AdminBookingSalesReport[]>(`/api/admin/reports/bookings?${monthParams}`),
      ])
      return {
        section: 'reports',
        report: reportData,
        bestSellingProducts: bestSellers,
        bestSellingServices: topServices,
        bookingSales: bookingsData,
      }
    }

    void fetchData().then((data) => {
      if (!active) return
      if (data.section === 'accounts') {
        setCustomers(data.customers)
        setPayments(data.payments)
      } else if (data.section === 'expenses') {
        setExpenses(data.expenses)
      } else {
        setReport(data.report)
        setBestSellingProducts(data.bestSellingProducts)
        setBestSellingServices(data.bestSellingServices)
        setBookingSales(data.bookingSales)
      }
    }).catch((cause: unknown) => {
      if (active) setError(getErrorMessage(cause, 'تعذر تحميل البيانات المالية.'))
    })
    return () => { active = false }
  }, [fromDate, query, refreshKey, reportMonth, section, toDate])

  const sendCustomerDebtReminder = async (customerId: string, hasAccount: boolean) => {
    setReminderBusyId(customerId)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/debts/customers/${customerId}/reminder?type=${hasAccount ? 'ACCOUNTS' : 'GUESTS'}`, {
        method: 'POST',
      })
      setNotice('تم إرسال تذكير بإجمالي المبلغ المستحق عبر الإشعارات.')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إرسال التذكير.'))
    } finally {
      setReminderBusyId('')
    }
  }

  const addManualDebt = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest('/api/admin/manual-debts', {
        method: 'POST',
        body: JSON.stringify({
          customerName: String(formData.get('customerName')).trim(),
          phoneNumber: String(formData.get('phoneNumber')).trim(),
          description: String(formData.get('description')).trim(),
          amount: Number(formData.get('amount')),
        }),
      })
      form.reset()
      setAddDebtOpen(false)
      setNotice('تمت إضافة الدين إلى حساب العميل.')
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إضافة الدين.'))
    } finally {
      setBusy(false)
    }
  }

  const showCustomerDetails = async (customer: AdminCustomer) => {
    setError('')
    try {
      const type = customer.hasAccount ? 'ACCOUNTS' : 'GUESTS'
      const details = await apiRequest<AdminCustomerDetails>(
        `/api/admin/customers/${customer.id}?type=${type}`,
      )
      setSelectedCustomerDetails(details)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل تفاصيل حساب العميل.'))
    }
  }

  const submitCustomerPayment = async (
    event: FormEvent<HTMLFormElement>, customerId: string, hasAccount: boolean,
  ) => {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    setCustomerPaymentBusyId(customerId)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/debts/customers/${customerId}/payments?type=${hasAccount ? 'ACCOUNTS' : 'GUESTS'}`, {
        method: 'POST',
        body: JSON.stringify({
          amount: Number(formData.get('amount')),
          method: String(formData.get('method')),
          notes: formData.get('notes') ? String(formData.get('notes')).trim() || null : null,
        }),
      })
      form.reset()
      setNotice('تم تسجيل الدفعة وخصمها من إجمالي دين العميل.')
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تسجيل الدفعة.'))
    } finally {
      setCustomerPaymentBusyId('')
    }
  }

  const createExpense = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest('/api/admin/expenses', {
        method: 'POST',
        body: JSON.stringify({
          category: String(formData.get('category')).trim(),
          description: String(formData.get('description')).trim(),
          amount: Number(formData.get('amount')),
          expenseDate: String(formData.get('expenseDate')),
          method: String(formData.get('method')),
          notes: String(formData.get('notes')).trim() || null,
        }),
      })
      setNotice('تم تسجيل المصروف.')
      form.reset()
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تسجيل المصروف.'))
    } finally {
      setBusy(false)
    }
  }

  const indebtedCustomers = customers.filter((customer) => customer.outstandingDebt > 0)
  const totalCustomerDebt = indebtedCustomers.reduce((sum, customer) => sum + customer.outstandingDebt, 0)
  const unpaidBookingDetails = selectedCustomerDetails?.bookings.filter(
    (booking) => booking.status === 'COMPLETED' && booking.outstandingBalance > 0,
  ) ?? []
  const unpaidManualDebts = selectedCustomerDetails?.manualDebts.filter((debt) => debt.outstandingBalance > 0) ?? []

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">{labels[section].eyebrow}</span><h2>{labels[section].title}</h2></div>
          {section === 'reports' && <div className="admin-date-range">
            <label>الشهر<input type="month" value={reportMonth}
              onChange={(event) => setReportMonth(event.target.value || getShopDate().slice(0, 7))} /></label>
          </div>}
          {(section === 'accounts' || section === 'expenses') && <div className="admin-date-range">
            <label>من<input type="date" value={fromDate} max={toDate}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => setFromDate(event.target.value)} /></label>
            <label>إلى<input type="date" value={toDate} min={fromDate}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => setToDate(event.target.value)} /></label>
          </div>}
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {section === 'accounts' && (
          <label className="admin-search admin-finance-search">
            <span className="visually-hidden">بحث في الحسابات والديون</span>
            <input value={query} maxLength={150} placeholder="ابحث باسم العميل أو رقم الهاتف"
              onChange={(event) => {
                setError('')
                setQuery(event.target.value)
              }} />
          </label>
        )}

        {section === 'accounts' && (
          <>
            <button className="admin-secondary-button admin-add-debt-trigger" type="button"
              onClick={() => setAddDebtOpen(true)}><Plus size={15} /> إضافة دين يدوي</button>
            <div className="admin-summary-chips">
              <span>{customers.length.toLocaleString('ar')} عميل</span>
              <span>{indebtedCustomers.length.toLocaleString('ar')} عميل عليه دين</span>
              <span>إجمالي الديون {totalCustomerDebt.toLocaleString('ar')} ₪</span>
            </div>
            <div className="admin-finance-list">
              {indebtedCustomers.map((customer) => (
                <article className="admin-debt-row" key={`${customer.hasAccount ? 'account' : 'guest'}-${customer.id}`}>
                  <div><strong>{customer.name}</strong>
                    <small>{customer.hasAccount ? 'حساب مسجل' : 'حجوزات ضيف'} · {customer.visits.toLocaleString('ar')} زيارة</small>
                    <small dir="ltr">{customer.phoneNumber || '—'}</small></div>
                  <strong className="admin-debt-value">إجمالي الدين {customer.outstandingDebt.toLocaleString('ar')} ₪</strong>
                  <div className="admin-debt-actions">
                    <div className="admin-debt-action-row">
                      <button className="admin-debt-reminder" type="button"
                        disabled={reminderBusyId === customer.id}
                        onClick={() => void sendCustomerDebtReminder(customer.id, customer.hasAccount)}>
                        <BellRing size={16} />
                        {reminderBusyId === customer.id ? 'جارٍ الإرسال...' : 'تذكير بالإجمالي عبر الإشعار'}
                      </button>
                      <button className="admin-icon-button admin-debt-detail-trigger" type="button"
                        aria-label={`عرض الحركات غير المسددة لـ ${customer.name}`} title="الحركات غير المسددة"
                        onClick={() => void showCustomerDetails(customer)}><Eye size={16} /></button>
                    </div>
                    <form className="admin-debt-payment" onSubmit={(event) =>
                      void submitCustomerPayment(event, customer.id, customer.hasAccount)}>
                      <input name="amount" aria-label={`المبلغ المدفوع من ${customer.name}`} type="number"
                        min="0.01" max={customer.outstandingDebt} step="0.01" placeholder="مبلغ الدفعة" required />
                      <select name="method" aria-label="طريقة الدفع">{methods.map((method) =>
                        <option key={method.value} value={method.value}>{method.label}</option>)}</select>
                      <button className="admin-secondary-button" disabled={customerPaymentBusyId === customer.id}>
                        {customerPaymentBusyId === customer.id ? 'جارٍ التسجيل...' : 'تسجيل دفعة'}
                      </button>
                    </form>
                  </div>
                </article>
              ))}
              {!indebtedCustomers.length
                && <p className="admin-empty-inline">لا توجد أرصدة مستحقة حالياً.</p>}
            </div>
            <div className="admin-finance-ledger-toggle-row">
              <button className="admin-secondary-button admin-finance-ledger-toggle" type="button"
                aria-expanded={paymentsOpen} onClick={() => setPaymentsOpen((open) => !open)}>
                <History size={15} /> {paymentsOpen ? 'إخفاء سجل الدفعات' : 'عرض سجل الدفعات'}
                <span>{payments.length.toLocaleString('ar')}</span>
              </button>
            </div>
            {paymentsOpen && <>
              <h3 className="admin-subheading">سجل الدفعات</h3>
              <div className="admin-finance-list">
                {payments.map((payment) => <div className="admin-finance-row" key={payment.id}>
                  <div><strong>{payment.customerName}</strong><small>{payment.notes || payment.method}</small></div>
                  <span>{new Date(payment.paidAt).toLocaleString('ar')}</span>
                  <strong>{payment.amount.toLocaleString('ar')} ₪</strong>
                </div>)}
                {!payments.length && <p className="admin-empty-inline">لا توجد دفعات في الفترة المحددة.</p>}
              </div>
            </>}
          </>
        )}

        {section === 'expenses' && (
          <div className="admin-finance-layout">
            <div className="admin-finance-list">
              {expenses.map((expense) => <div className="admin-finance-row" key={expense.id}>
                <div><strong>{expense.description}</strong><small>{expense.category} · {expense.method}</small></div>
                <span>{expense.expenseDate}</span><strong>{expense.amount.toLocaleString('ar')} ₪</strong>
              </div>)}
              {!expenses.length && <p className="admin-empty-inline">لا توجد مصروفات مسجلة.</p>}
            </div>
            <form className="admin-form" onSubmit={(event) => void createExpense(event)}>
              <h3>تسجيل مصروف</h3>
              <label>التصنيف<input name="category" maxLength={80} placeholder="إيجار، أدوات، فواتير..." required /></label>
              <label>البيان<input name="description" maxLength={500} required /></label>
              <label>المبلغ (₪)<input name="amount" type="number" min="0.01" step="0.01" required /></label>
              <label>التاريخ<input name="expenseDate" type="date" defaultValue={getShopDate()}
                onClick={(event) => event.currentTarget.showPicker?.()} required /></label>
              <label>طريقة الدفع<select name="method">{methods.map((method) =>
                <option key={method.value} value={method.value}>{method.label}</option>)}</select></label>
              <label>ملاحظات<input name="notes" maxLength={1000} /></label>
              <button className="admin-primary-button" disabled={busy}>{busy ? 'جارٍ الحفظ...' : 'حفظ المصروف'}</button>
            </form>
          </div>
        )}

        {section === 'reports' && (
          !report ? <p className="admin-state">جارٍ تحميل التقرير...</p> : (
            <div className="admin-report-content">
              <div className="admin-report-grid">
                <article><CreditCard size={19} /><span>الإيرادات المحصلة</span><strong>{report.collectedRevenue.toLocaleString('ar')} ₪</strong></article>
                <article><BarChart3 size={19} /><span>المصروفات</span><strong>{report.expenses.toLocaleString('ar')} ₪</strong></article>
                <article className="admin-report-profit"><CircleDollarSign size={19} /><span>صافي التدفق</span>
                  <strong>{report.netProfit.toLocaleString('ar')} ₪</strong></article>
                <article><Wallet size={19} /><span>ديون غير محصلة</span><strong>{report.outstandingDebt.toLocaleString('ar')} ₪</strong></article>
                <p className="admin-report-note">الإيراد يُحتسب عند تسجيل الدفع فقط؛ الحجوزات غير المدفوعة لا تدخل في الإيرادات.</p>
              </div>
              <section className="admin-report-detail">
                <h3>الخدمات الأكثر طلباً خلال الشهر</h3>
                {bestSellingServices.length ? bestSellingServices.map((service) => (
                  <div className="admin-report-product-row" key={service.serviceName}>
                    <strong>{service.serviceName}</strong>
                    <span>عدد الحجوزات: {service.bookingCount.toLocaleString('ar')}</span>
                    <span>قيمة الخدمات: {service.serviceRevenue.toLocaleString('ar')} ₪</span>
                  </div>
                )) : <p className="admin-state">لا توجد خدمات مكتملة في هذا الشهر.</p>}
              </section>
              <section className="admin-report-detail">
                <h3>الأكثر مبيعاً خلال الشهر</h3>
                {bestSellingProducts.length ? bestSellingProducts.map((product) => (
                  <div className="admin-report-product-row" key={`${product.productName}-${product.unit}`}>
                    <strong>{product.productName}</strong>
                    <span>الكمية: {product.quantitySold.toLocaleString('ar')} {product.unit}</span>
                    <span>المبيعات: {product.salesTotal.toLocaleString('ar')} ₪</span>
                    <small>{product.bookingCount.toLocaleString('ar')} فاتورة</small>
                  </div>
                )) : <p className="admin-state">لا توجد منتجات مباعة في هذا الشهر.</p>}
              </section>
              <section className="admin-report-detail">
                <div><h3>تفاصيل الخدمات والمنتجات المباعة</h3>
                  <small className="admin-report-invoice-hint">آخر 500 فاتورة مكتملة خلال الشهر المحدد</small></div>
                {bookingSales.length ? bookingSales.map((booking) => (
                  <article className="admin-report-invoice" key={booking.bookingId}>
                    <div className="admin-report-invoice-heading">
                      <strong>{booking.customerName}</strong>
                      <small>{new Date(booking.completedAt).toLocaleString('ar')}</small>
                    </div>
                    <div className="admin-report-product-row">
                      <strong>{booking.serviceName}</strong>
                      <span>الخدمة</span>
                      <span>السعر: {booking.servicePrice.toLocaleString('ar')} ₪</span>
                    </div>
                    {booking.products.map((product, index) => (
                      <div className="admin-report-product-row" key={`${product.productName}-${index}`}>
                        <strong>{product.productName}</strong>
                        <span>الكمية: {product.quantity.toLocaleString('ar')} {product.unit}</span>
                        <span>سعر الوحدة: {product.unitPrice.toLocaleString('ar')} ₪</span>
                        <small>الإجمالي: {product.lineTotal.toLocaleString('ar')} ₪</small>
                      </div>
                    ))}
                    <div className="admin-report-invoice-total">
                      <span>الإجمالي {booking.invoiceTotal.toLocaleString('ar')} ₪</span>
                      <span>المقبوض {booking.amountPaid.toLocaleString('ar')} ₪</span>
                      {booking.balance > 0 && <strong>المتبقي {booking.balance.toLocaleString('ar')} ₪</strong>}
                    </div>
                  </article>
                )) : <p className="admin-state">لا توجد فواتير مكتملة في هذا الشهر.</p>}
              </section>
            </div>
          )
        )}
      </section>
      {addDebtOpen && createPortal(
        <div className="admin-booking-modal-backdrop admin-customer-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setAddDebtOpen(false)
          }}>
          <section className="admin-booking-modal admin-customer-modal" role="dialog" aria-modal="true"
            aria-labelledby="add-manual-debt-title" dir="rtl">
            <div className="admin-panel-heading">
              <div><span className="admin-eyebrow">الحسابات والديون</span>
                <h2 id="add-manual-debt-title">إضافة دين يدوي</h2></div>
              <button className="admin-icon-button" type="button" aria-label="إغلاق"
                onClick={() => setAddDebtOpen(false)}><X size={17} /></button>
            </div>
            <form className="admin-form admin-manual-debt-form" onSubmit={(event) => void addManualDebt(event)}>
              <label>اسم العميل<input name="customerName" maxLength={150} required /></label>
              <label>رقم الهاتف<input name="phoneNumber" type="tel" maxLength={32} required /></label>
              <label>سبب الدين<input name="description" maxLength={500} placeholder="خدمة، منتج، أو سبب آخر" required /></label>
              <label>المبلغ (₪)<input name="amount" type="number" min="0.01" step="0.01" required /></label>
              <button className="admin-primary-button" disabled={busy}>
                {busy ? 'جارٍ الحفظ...' : 'إضافة الدين'}
              </button>
            </form>
          </section>
        </div>,
        document.body,
      )}
      {selectedCustomerDetails && createPortal(
        <div className="admin-booking-modal-backdrop admin-customer-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setSelectedCustomerDetails(null)
          }}>
          <section className="admin-booking-modal admin-customer-modal" role="dialog" aria-modal="true"
            aria-labelledby="finance-customer-details-title" dir="rtl">
            <div className="admin-panel-heading">
              <div><span className="admin-eyebrow">كشف الحساب</span>
                <h2 id="finance-customer-details-title">{selectedCustomerDetails.customer.name}</h2></div>
              <button className="admin-icon-button" type="button" aria-label="إغلاق"
                onClick={() => setSelectedCustomerDetails(null)}><X size={17} /></button>
            </div>
            <p className="admin-contact-line" dir="ltr">{selectedCustomerDetails.customer.phoneNumber || '—'}</p>
            <div className="admin-summary-chips">
              <span>إجمالي المتبقي {selectedCustomerDetails.customer.outstandingDebt.toLocaleString('ar')} ₪</span>
            </div>
            <h3 className="admin-subheading">الحركات غير المسددة بالكامل</h3>
            {unpaidBookingDetails.map((booking) => (
              <article className="admin-customer-report-booking" key={booking.id}>
                <div className="admin-history-row">
                  <span>{booking.serviceName}</span><span>{booking.status}</span>
                  <strong>الإجمالي {booking.totalAmount.toLocaleString('ar')} ₪</strong>
                  <small>{new Date(booking.createdAt).toLocaleDateString('ar')}</small>
                </div>
                {booking.products.map((product, index) => (
                  <p className="admin-customer-purchases" key={`${product.productName}-${index}`}>
                    {product.productName} · {product.quantity.toLocaleString('ar')} {product.unit}
                    {' · '}{product.lineTotal.toLocaleString('ar')} ₪
                  </p>
                ))}
                <div className="admin-history-row">
                  <span>المدفوع {booking.paidAmount.toLocaleString('ar')} ₪</span>
                  <strong>المتبقي {booking.outstandingBalance.toLocaleString('ar')} ₪</strong>
                </div>
                {selectedCustomerDetails.payments.filter((payment) => payment.bookingId === booking.id).map((payment) => (
                  <small className="admin-customer-purchases" key={payment.id}>
                    دفعة {payment.amount.toLocaleString('ar')} ₪ · {payment.method}
                    {payment.notes ? ` · ${payment.notes}` : ''}
                  </small>
                ))}
              </article>
            ))}
            {unpaidManualDebts.map((debt) => (
              <article className="admin-customer-report-booking" key={debt.id}>
                <strong>دين يدوي · {debt.description}</strong>
                <div className="admin-history-row">
                  <span>المبلغ {debt.totalAmount.toLocaleString('ar')} ₪</span>
                  <span>المدفوع {debt.paidAmount.toLocaleString('ar')} ₪</span>
                  <strong>المتبقي {debt.outstandingBalance.toLocaleString('ar')} ₪</strong>
                  <small>{new Date(debt.createdAt).toLocaleDateString('ar')}</small>
                </div>
                {debt.payments.map((payment) => <small className="admin-customer-purchases" key={payment.id}>
                  دفعة {payment.amount.toLocaleString('ar')} ₪ · {payment.method}
                  {payment.notes ? ` · ${payment.notes}` : ''}
                </small>)}
              </article>
            ))}
            {!unpaidBookingDetails.length && !unpaidManualDebts.length
              && <p className="admin-state">لا توجد حركات عليها مبلغ متبقٍ.</p>}
          </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminFinancePage
