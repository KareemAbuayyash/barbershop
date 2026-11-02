import { useEffect, useState, type FormEvent } from 'react'
import { BarChart3, CircleDollarSign, CreditCard, Wallet } from 'lucide-react'
import { apiRequest, getErrorMessage, type BookingPage } from '../../../lib/api'
import { getShopDate } from '../../../lib/shop-time'
import type {
  AdminBestSellingProduct,
  AdminBestSellingService,
  AdminBookingSalesReport,
  AdminCustomer,
  AdminDebt,
  AdminExpense,
  AdminPayment,
  AdminReport,
} from '../admin-api'

type Section = 'accounts' | 'payments' | 'debts' | 'expenses' | 'reports'
type FinanceLoadResult =
  | { section: 'accounts'; customers: AdminCustomer[]; debts: AdminDebt[] }
  | { section: 'payments'; payments: AdminPayment[]; bookings: BookingPage['content']; debts: AdminDebt[] }
  | { section: 'debts'; debts: AdminDebt[] }
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
  accounts: { eyebrow: 'الأرصدة وسجل العملاء', title: 'الحسابات والذمم' },
  payments: { eyebrow: 'تسجيل الإيرادات المحصلة', title: 'المدفوعات' },
  debts: { eyebrow: 'مبالغ مستحقة على الحجوزات المكتملة', title: 'الديون' },
  expenses: { eyebrow: 'النفقات التشغيلية المسجلة', title: 'المصروفات' },
  reports: { eyebrow: 'ملخص نقدي حسب فترة التحصيل', title: 'الأرباح والتقارير' },
}

function AdminFinancePage({ section }: { section: Section }) {
  const [customers, setCustomers] = useState<AdminCustomer[]>([])
  const [debts, setDebts] = useState<AdminDebt[]>([])
  const [payments, setPayments] = useState<AdminPayment[]>([])
  const [expenses, setExpenses] = useState<AdminExpense[]>([])
  const [report, setReport] = useState<AdminReport | null>(null)
  const [bestSellingProducts, setBestSellingProducts] = useState<AdminBestSellingProduct[]>([])
  const [bestSellingServices, setBestSellingServices] = useState<AdminBestSellingService[]>([])
  const [bookingSales, setBookingSales] = useState<AdminBookingSalesReport[]>([])
  const [bookings, setBookings] = useState<BookingPage['content']>([])
  const [query, setQuery] = useState('')
  const [fromDate, setFromDate] = useState(getShopDate().slice(0, 7) + '-01')
  const [toDate, setToDate] = useState(getShopDate())
  const [reportMonth, setReportMonth] = useState(getShopDate().slice(0, 7))
  const [busy, setBusy] = useState(false)
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
        const debtParams = new URLSearchParams()
        if (query.trim()) debtParams.set('q', query.trim())
        const [customerPage, unpaid] = await Promise.all([
          apiRequest<{ content: AdminCustomer[] }>(`/api/admin/customers?${searchParams}`),
          apiRequest<AdminDebt[]>(`/api/admin/debts?${debtParams}`),
        ])
        return { section, customers: customerPage.content, debts: unpaid }
      } else if (section === 'payments') {
        const [paid, completed, unpaid] = await Promise.all([
          apiRequest<AdminPayment[]>(`/api/admin/payments?${params}`),
          apiRequest<BookingPage>(`/api/bookings?status=COMPLETED&page=0&size=100`),
          apiRequest<AdminDebt[]>('/api/admin/debts'),
        ])
        return {
          section,
          payments: paid,
          debts: unpaid,
          bookings: completed.content.filter((booking) => unpaid.some((debt) => debt.bookingId === booking.id)),
        }
      } else if (section === 'debts') {
        const debtParams = new URLSearchParams()
        if (query.trim()) debtParams.set('q', query.trim())
        return { section, debts: await apiRequest<AdminDebt[]>(`/api/admin/debts?${debtParams}`) }
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
        setDebts(data.debts)
      } else if (data.section === 'payments') {
        setPayments(data.payments)
        setBookings(data.bookings)
        setDebts(data.debts)
      } else if (data.section === 'debts') {
        setDebts(data.debts)
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

  const submitPayment = async (event: FormEvent<HTMLFormElement>, bookingId: string, debt = false) => {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/${debt ? 'debts' : 'bookings'}/${bookingId}/payments`, {
        method: 'POST',
        body: JSON.stringify({
          amount: Number(formData.get('amount')),
          method: String(formData.get('method')),
          notes: formData.get('notes') ? String(formData.get('notes')).trim() || null : null,
        }),
      })
      setNotice('تم تسجيل الدفعة في السجل المالي.')
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تسجيل الدفعة.'))
    } finally {
      setBusy(false)
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

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">{labels[section].eyebrow}</span><h2>{labels[section].title}</h2></div>
          {section === 'reports' && <div className="admin-date-range">
            <label>الشهر<input type="month" value={reportMonth}
              onChange={(event) => setReportMonth(event.target.value || getShopDate().slice(0, 7))} /></label>
          </div>}
          {(section === 'payments' || section === 'expenses') && <div className="admin-date-range">
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
        {(section === 'accounts' || section === 'debts') && (
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
            <div className="admin-summary-chips">
              <span>{customers.length.toLocaleString('ar')} عميل</span>
              <span>{debts.length.toLocaleString('ar')} رصيد مستحق</span>
              <span>إجمالي الدين {debts.reduce((sum, item) => sum + item.balance, 0).toLocaleString('ar')} ₪</span>
            </div>
            <div className="admin-finance-list">
              {indebtedCustomers.map((customer) => (
                <article className="admin-finance-row" key={customer.id}>
                  <div><strong>{customer.name}</strong><small dir="ltr">{customer.phoneNumber || '—'}</small></div>
                  <span>{customer.visits.toLocaleString('ar')} زيارة</span>
                  <strong className="admin-debt-value">{customer.outstandingDebt.toLocaleString('ar')} ₪</strong>
                </article>
              ))}
              {!indebtedCustomers.length
                && <p className="admin-empty-inline">لا توجد أرصدة مستحقة حالياً.</p>}
            </div>
          </>
        )}

        {section === 'payments' && (
          <>
            <div className="admin-payment-entry">
              <h3><CreditCard size={18} /> تسجيل دفعة لحجز مكتمل</h3>
              {!bookings.length ? <p className="admin-state">لا توجد حجوزات مكتملة بانتظار الدفع.</p> : (
                <form className="admin-payment-form" onSubmit={(event) => {
                  const form = event.currentTarget
                  void submitPayment(event, String(new FormData(form).get('bookingId')))
                }}>
                  <label>الحجز<select name="bookingId" required defaultValue="">
                    <option value="" disabled>اختر الحجز</option>
                    {bookings.map((booking) => {
                      const debt = debts.find((item) => item.bookingId === booking.id)
                      return (
                        <option value={booking.id} key={booking.id}>
                          {booking.customerName || 'عميل'} — {booking.serviceName} — المتبقي {debt?.balance.toLocaleString('ar')} ₪
                        </option>
                      )
                    })}
                  </select></label>
                  <label>المبلغ<input name="amount" type="number" min="0.01" step="0.01" required /></label>
                  <label>طريقة الدفع<select name="method">{methods.map((method) =>
                    <option key={method.value} value={method.value}>{method.label}</option>)}</select></label>
                  <label>ملاحظة<input name="notes" maxLength={1000} /></label>
                  <button className="admin-primary-button" disabled={busy}>{busy ? 'جارٍ الحفظ...' : 'تسجيل الدفعة'}</button>
                </form>
              )}
            </div>
            <h3 className="admin-subheading">سجل الدفعات</h3>
            <div className="admin-finance-list">
              {payments.map((payment) => <div className="admin-finance-row" key={payment.id}>
                <div><strong>{payment.customerName}</strong><small>{payment.notes || payment.method}</small></div>
                <span>{new Date(payment.paidAt).toLocaleString('ar')}</span>
                <strong>{payment.amount.toLocaleString('ar')} ₪</strong>
              </div>)}
              {!payments.length && <p className="admin-empty-inline">لا توجد دفعات في الفترة المحددة.</p>}
            </div>
          </>
        )}

        {section === 'debts' && (
          <div className="admin-finance-list">
            {debts.map((debt) => <article className="admin-debt-row" key={debt.bookingId}>
              <div><strong>{debt.customerName}</strong><small>{debt.serviceName} · {debt.servicePrice.toLocaleString('ar')} ₪</small>
                {debt.phoneNumber && <small dir="ltr">{debt.phoneNumber}</small>}</div>
              <strong className="admin-debt-value">المتبقي {debt.balance.toLocaleString('ar')} ₪</strong>
              <form className="admin-debt-payment" onSubmit={(event) => void submitPayment(event, debt.bookingId, true)}>
                <input name="amount" aria-label="المبلغ المحصل" type="number" min="0.01" max={debt.balance}
                  step="0.01" placeholder="المبلغ" required />
                <select name="method" aria-label="طريقة الدفع">{methods.map((method) =>
                  <option key={method.value} value={method.value}>{method.label}</option>)}</select>
                <button className="admin-secondary-button" disabled={busy}>تسديد</button>
              </form>
            </article>)}
            {!debts.length && <p className="admin-empty-inline">لا توجد ديون مستحقة.</p>}
          </div>
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
    </div>
  )
}

export default AdminFinancePage
