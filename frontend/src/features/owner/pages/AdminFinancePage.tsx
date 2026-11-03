import { useEffect, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { BarChart3, BellRing, ChevronDown, ChevronLeft, ChevronRight, CircleDollarSign, CreditCard, Eye, History, Pencil, Plus, Trash2, Wallet, X } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import { getShopDate } from '../../../lib/shop-time'
import { useI18n } from '../../../lib/i18n'
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

const paymentMethodNames: Record<string, string> = {
  CASH: 'نقداً', CARD: 'بطاقة', TRANSFER: 'تحويل', OTHER: 'أخرى',
}

function paymentMethodLabel(method: string, t: (value: string) => string) {
  return t(paymentMethodNames[method] ?? method)
}

const bookingStatusNames: Record<string, string> = {
  PENDING: 'بانتظار التأكيد', CONFIRMED: 'مؤكد', CANCELLED: 'ملغي', COMPLETED: 'مكتمل',
}

const labels: Record<Section, { eyebrow: string; title: string }> = {
  accounts: { eyebrow: 'أرصدة العملاء والدفعات المحصلة', title: 'الحسابات والديون' },
  expenses: { eyebrow: 'النفقات التشغيلية المسجلة', title: 'المصروفات' },
  reports: { eyebrow: 'ملخص نقدي حسب فترة التحصيل', title: 'الأرباح والتقارير' },
}

const reportChartColors = ['#ddc794', '#a88b55', '#806b43', '#685b40', '#4c4838']

function ReportDonut({ values, labels, centerValue, centerLabel, label, valueUnit }: {
  values: number[]
  labels: string[]
  centerValue: string
  centerLabel: string
  label: string
  valueUnit: string
}) {
  const { t, language } = useI18n()
  const [hoveredSegment, setHoveredSegment] = useState<number | null>(null)
  const [tooltipPosition, setTooltipPosition] = useState({ x: 0, y: 0 })
  const total = values.reduce((sum, value) => sum + Math.max(0, value), 0)
  return (
    <div className="admin-report-donut-wrap">
      <svg className="admin-report-donut" viewBox="0 0 120 120" role="img" aria-label={label}
        onMouseMove={(event) => {
          const bounds = event.currentTarget.getBoundingClientRect()
          setTooltipPosition({ x: event.clientX - bounds.left, y: event.clientY - bounds.top })
        }}
        onMouseLeave={() => setHoveredSegment(null)}>
        <circle cx="60" cy="60" r="43" className="admin-report-donut-track" />
        {total > 0 && values.map((value, index) => {
          const share = Math.max(0, value) / total * 100
          const offset = values.slice(0, index).reduce((sum, previous) => sum + Math.max(0, previous), 0) / total * 100
          return <circle key={index} cx="60" cy="60" r="43" pathLength="100"
            className="admin-report-donut-segment" stroke={reportChartColors[index % reportChartColors.length]}
            strokeDasharray={`${share} ${100 - share}`} strokeDashoffset={-offset}
            tabIndex={0} role="button"
            aria-label={`${labels[index] ?? t(label)}: ${value.toLocaleString(language)} ${t(valueUnit)}, ${share.toLocaleString(language, { maximumFractionDigits: 1 })}%`}
            onMouseEnter={() => setHoveredSegment(index)}
            onFocus={() => setHoveredSegment(index)} onBlur={() => setHoveredSegment(null)}
            onClick={() => setHoveredSegment((current) => current === index ? null : index)}>
            <title>{`${labels[index] ?? t(label)}: ${value.toLocaleString(language)} ${t(valueUnit)} (${share.toLocaleString(language, { maximumFractionDigits: 1 })}%)`}</title>
          </circle>
        })}
        <text x="60" y="56" className="admin-report-donut-value" textAnchor="middle" dominantBaseline="middle">{centerValue}</text>
        <text x="60" y="73" className="admin-report-donut-label" textAnchor="middle" dominantBaseline="middle">{t(centerLabel)}</text>
      </svg>
      {hoveredSegment !== null && values[hoveredSegment] !== undefined && <div className="admin-report-chart-tooltip"
        style={{ left: tooltipPosition.x, top: tooltipPosition.y }} role="status">
        <span className="admin-report-mix-swatch" style={{ backgroundColor: reportChartColors[hoveredSegment % reportChartColors.length] }} />
        <strong>{labels[hoveredSegment] ?? t(label)}</strong>
        <span>{values[hoveredSegment].toLocaleString(language)} {t(valueUnit)}</span>
        <small>{(Math.max(0, values[hoveredSegment]) / total * 100).toLocaleString(language, { maximumFractionDigits: 1 })}٪</small>
      </div>}
      <span>{t(label)}</span>
    </div>
  )
}

function AdminFinancePage({ section }: { section: Section }) {
  const { t, language } = useI18n()
  const [customers, setCustomers] = useState<AdminCustomer[]>([])
  const [selectedCustomerDetails, setSelectedCustomerDetails] = useState<AdminCustomerDetails | null>(null)
  const [addDebtOpen, setAddDebtOpen] = useState(false)
  const [expenseOpen, setExpenseOpen] = useState(false)
  const [editingExpense, setEditingExpense] = useState<AdminExpense | null>(null)
  const [payments, setPayments] = useState<AdminPayment[]>([])
  const [paymentsOpen, setPaymentsOpen] = useState(false)
  const [expenses, setExpenses] = useState<AdminExpense[]>([])
  const [report, setReport] = useState<AdminReport | null>(null)
  const [bestSellingProducts, setBestSellingProducts] = useState<AdminBestSellingProduct[]>([])
  const [bestSellingServices, setBestSellingServices] = useState<AdminBestSellingService[]>([])
  const [bookingSales, setBookingSales] = useState<AdminBookingSalesReport[]>([])
  const [invoicePage, setInvoicePage] = useState(0)
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
      if (active) setError(t(getErrorMessage(cause, 'تعذر تحميل البيانات المالية.')))
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
      setNotice(t('تم إرسال تذكير بإجمالي المبلغ المستحق عبر الإشعارات.'))
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر إرسال التذكير.')))
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
      setNotice(t('تمت إضافة الدين إلى حساب العميل.'))
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر إضافة الدين.')))
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
      setError(t(getErrorMessage(cause, 'تعذر تحميل تفاصيل حساب العميل.')))
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
      setNotice(t('تم تسجيل الدفعة وخصمها من إجمالي دين العميل.'))
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تسجيل الدفعة.')))
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
      await apiRequest(editingExpense ? `/api/admin/expenses/${editingExpense.id}` : '/api/admin/expenses', {
        method: editingExpense ? 'PUT' : 'POST',
        body: JSON.stringify({
          category: String(formData.get('category')).trim(),
          description: String(formData.get('description')).trim(),
          amount: Number(formData.get('amount')),
          expenseDate: String(formData.get('expenseDate')),
          method: String(formData.get('method')),
          notes: String(formData.get('notes')).trim() || null,
        }),
      })
      setNotice(t(editingExpense ? 'تم تعديل المصروف.' : 'تم تسجيل المصروف.'))
      form.reset()
      setExpenseOpen(false)
      setEditingExpense(null)
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تسجيل المصروف.')))
    } finally {
      setBusy(false)
    }
  }

  const deleteExpense = async (expense: AdminExpense) => {
    const confirmation = language === 'ar'
      ? `حذف المصروف «${expense.description}» بقيمة ${expense.amount.toLocaleString(language)} ₪؟`
      : `Delete expense “${expense.description}” for ₪${expense.amount.toLocaleString(language)}?`
    if (!window.confirm(confirmation)) return
    setError('')
    setNotice('')
    try {
      await apiRequest(`/api/admin/expenses/${expense.id}`, { method: 'DELETE' })
      setNotice(t('تم حذف المصروف.'))
      setRefreshKey((value) => value + 1)
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر حذف المصروف.')))
    }
  }

  const indebtedCustomers = customers.filter((customer) => customer.outstandingDebt > 0)
  const totalCustomerDebt = indebtedCustomers.reduce((sum, customer) => sum + customer.outstandingDebt, 0)
  const unpaidBookingDetails = selectedCustomerDetails?.bookings.filter(
    (booking) => booking.status === 'COMPLETED' && booking.outstandingBalance > 0,
  ) ?? []
  const unpaidManualDebts = selectedCustomerDetails?.manualDebts.filter((debt) => debt.outstandingBalance > 0) ?? []

  return (
    <div className={`admin-page admin-finance-page${section === 'reports' ? ' admin-finance-page--reports' : ''}`}>
      <section className={`admin-panel${section === 'reports' ? ' admin-finance-main admin-finance-main--reports' : ''}`}>
        <div className={`admin-panel-heading${section === 'reports' ? ' admin-report-page-heading' : ''}`}>
          <div><span className="admin-eyebrow">{t(labels[section].eyebrow)}</span><h2>{t(labels[section].title)}</h2></div>
          {section === 'reports' && <div className="admin-date-range admin-report-period">
            <label>{t('الفترة')}<input type="month" value={reportMonth}
              onChange={(event) => {
                setReportMonth(event.target.value || getShopDate().slice(0, 7))
                setInvoicePage(0)
              }} /></label>
          </div>}
          {(section === 'accounts' || section === 'expenses') && <div className="admin-date-range">
            <label>{t('من')}<input type="date" value={fromDate} max={toDate}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => setFromDate(event.target.value)} /></label>
            <label>{t('إلى')}<input type="date" value={toDate} min={fromDate}
              onClick={(event) => event.currentTarget.showPicker?.()}
              onChange={(event) => setToDate(event.target.value)} /></label>
          </div>}
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{t(error)}</p>}
        {notice && <p className="admin-message" role="status">{t(notice)}</p>}
        {section === 'accounts' && (
          <label className="admin-search admin-finance-search">
            <span className="visually-hidden">{language === 'ar' ? 'بحث في الحسابات والديون' : 'Search accounts and debts'}</span>
            <input value={query} maxLength={150} placeholder={t('ابحث باسم العميل أو رقم الهاتف')}
              onChange={(event) => {
                setError('')
                setQuery(event.target.value)
              }} />
          </label>
        )}

        {section === 'accounts' && (
          <>
            <button className="admin-secondary-button admin-add-debt-trigger" type="button"
              onClick={() => setAddDebtOpen(true)}><Plus size={15} /> {language === 'ar' ? 'إضافة دين يدوي' : 'Add manual debt'}</button>
            <div className="admin-summary-chips">
              <span>{customers.length.toLocaleString(language)} {t('عميل')}</span>
              <span>{indebtedCustomers.length.toLocaleString(language)} {t('عميل عليه دين')}</span>
              <span>{t('إجمالي الديون')} {totalCustomerDebt.toLocaleString(language)} ₪</span>
            </div>
            <div className="admin-finance-list">
              {indebtedCustomers.map((customer) => (
                <article className="admin-debt-row" key={`${customer.hasAccount ? 'account' : 'guest'}-${customer.id}`}>
                  <div><strong>{customer.name}</strong>
                    <small>{t(customer.hasAccount ? 'حساب مسجل' : 'حجوزات ضيف')} · {customer.visits.toLocaleString(language)} {t('زيارة')}</small>
                    <small dir="ltr">{customer.phoneNumber || '—'}</small></div>
                  <strong className="admin-debt-value">{t('إجمالي الدين')} {customer.outstandingDebt.toLocaleString(language)} ₪</strong>
                  <div className="admin-debt-actions">
                    <div className="admin-debt-action-row">
                      <button className="admin-debt-reminder" type="button"
                        disabled={reminderBusyId === customer.id}
                        onClick={() => void sendCustomerDebtReminder(customer.id, customer.hasAccount)}>
                        <BellRing size={16} />
                        {reminderBusyId === customer.id ? t('جارٍ الإرسال...') : t('تذكير بالإجمالي عبر الإشعار')}
                      </button>
                      <button className="admin-icon-button admin-debt-detail-trigger" type="button"
                        aria-label={`${t('عرض الحركات غير المسددة لـ')} ${customer.name}`} title={t('الحركات غير المسددة')}
                        onClick={() => void showCustomerDetails(customer)}><Eye size={16} /></button>
                    </div>
                    <form className="admin-debt-payment" onSubmit={(event) =>
                      void submitCustomerPayment(event, customer.id, customer.hasAccount)}>
                      <input name="amount" aria-label={`${t('المبلغ المدفوع من')} ${customer.name}`} type="number"
                        min="0.01" max={customer.outstandingDebt} step="0.01" placeholder={t('مبلغ الدفعة')} required />
                      <select name="method" aria-label={t('طريقة الدفع')}>{methods.map((method) =>
                        <option key={method.value} value={method.value}>{t(method.label)}</option>)}</select>
                      <button className="admin-secondary-button" disabled={customerPaymentBusyId === customer.id}>
                        {customerPaymentBusyId === customer.id ? t('جارٍ التسجيل...') : t('تسجيل دفعة')}
                      </button>
                    </form>
                  </div>
                </article>
              ))}
              {!indebtedCustomers.length
                && <p className="admin-empty-inline">{t('لا توجد أرصدة مستحقة حالياً.')}</p>}
            </div>
            <div className="admin-finance-ledger-toggle-row">
              <button className="admin-secondary-button admin-finance-ledger-toggle" type="button"
                aria-expanded={paymentsOpen} onClick={() => setPaymentsOpen((open) => !open)}>
                <History size={15} /> {t(paymentsOpen ? 'إخفاء سجل الدفعات' : 'عرض سجل الدفعات')}
                <span>{payments.length.toLocaleString(language)}</span>
              </button>
            </div>
            {paymentsOpen && <>
              <h3 className="admin-subheading">{t('سجل الدفعات')}</h3>
              <div className="admin-finance-list">
                {payments.map((payment) => <div className="admin-finance-row" key={payment.id}>
                  <div><strong>{payment.customerName}</strong><small>{payment.notes || paymentMethodLabel(payment.method, t)}</small></div>
                  <span>{new Date(payment.paidAt).toLocaleString(language)}</span>
                  <strong>{payment.amount.toLocaleString(language)} ₪</strong>
                </div>)}
                {!payments.length && <p className="admin-empty-inline">{t('لا توجد دفعات في الفترة المحددة.')}</p>}
              </div>
            </>}
          </>
        )}

        {section === 'expenses' && (
          <div className="admin-expenses-view">
            <button className="admin-secondary-button admin-expense-trigger" type="button"
              onClick={() => { setEditingExpense(null); setExpenseOpen(true) }}><Plus size={15} /> {t('تسجيل مصروف')}</button>
            <div className="admin-finance-list">
              {expenses.map((expense) => <div className="admin-finance-row" key={expense.id}>
                <div><strong>{expense.description}</strong><small>{expense.category} · {paymentMethodLabel(expense.method, t)}</small></div>
                <span>{expense.expenseDate}</span><strong>{expense.amount.toLocaleString(language)} ₪</strong>
                <div className="admin-expense-actions">
                  <button className="admin-icon-button" type="button" aria-label={`${t('تعديل المصروف')} ${expense.description}`}
                    title={t('تعديل')} onClick={() => { setEditingExpense(expense); setExpenseOpen(true) }}><Pencil size={15} /></button>
                  <button className="admin-icon-button admin-icon-button--danger" type="button" aria-label={`${t('حذف المصروف')} ${expense.description}`}
                    title={t('حذف')} onClick={() => void deleteExpense(expense)}><Trash2 size={15} /></button>
                </div>
              </div>)}
              {!expenses.length && <p className="admin-empty-inline">{t('لا توجد مصروفات مسجلة.')}</p>}
            </div>
          </div>
        )}

        {section === 'reports' && (
          !report ? <p className="admin-state">{t('جارٍ تحميل التقرير...')}</p> : (
            <div className="admin-report-content">
              <div className="admin-report-grid">
                <article className="report-card--income"><CreditCard size={19} /><span>{t('الإيرادات المحصلة')}</span><strong>{report.collectedRevenue.toLocaleString(language)} ₪</strong></article>
                <article className="report-card--expense"><BarChart3 size={19} /><span>{t('المصروفات')}</span><strong>{report.expenses.toLocaleString(language)} ₪</strong></article>
                <article className="admin-report-profit report-card--profit"><CircleDollarSign size={19} /><span>{t('صافي التدفق')}</span>
                  <strong>{report.netProfit.toLocaleString(language)} ₪</strong></article>
                <article className="report-card--debt"><Wallet size={19} /><span>{t('ديون غير محصلة')}</span><strong>{report.outstandingDebt.toLocaleString(language)} ₪</strong></article>
              </div>
              <p className="admin-report-note">{t('الإيراد يُحتسب عند تسجيل الدفع فقط؛ الحجوزات غير المدفوعة لا تدخل في الإيرادات.')}</p>
              <div className="admin-report-highlights">
              <section className="admin-report-detail admin-report-highlight">
                <div className="admin-report-section-heading"><div><span className="admin-eyebrow">{t('الأداء')}</span>
                  <h3>{t('الخدمات الأكثر طلباً')}</h3></div><span className="admin-report-period-chip">{t('هذا الشهر')}</span></div>
                {bestSellingServices.length ? <div className="admin-report-highlight-layout">
                  <ReportDonut values={bestSellingServices.map((service) => service.bookingCount)}
                    labels={bestSellingServices.map((service) => service.serviceName)} valueUnit="حجز"
                    centerValue={bestSellingServices.reduce((total, service) => total + service.bookingCount, 0).toLocaleString(language)}
                    centerLabel="حجز" label="توزيع الحجوزات" />
                  <div className="admin-report-highlight-rows">{bestSellingServices.map((service) => (
                  <div className="admin-report-product-row" key={service.serviceName}>
                    <span className="admin-report-rank">{(bestSellingServices.indexOf(service) + 1).toLocaleString(language)}</span>
                    <div className="admin-report-product-main"><strong>{service.serviceName}</strong>
                      <small>{service.bookingCount.toLocaleString(language)} {t('حجز')}</small></div>
                    <strong className="admin-report-product-value">{service.serviceRevenue.toLocaleString(language)} ₪</strong>
                  </div>
                  ))}</div>
                </div> : <p className="admin-state">{t('لا توجد خدمات مكتملة في هذا الشهر.')}</p>}
              </section>
              <section className="admin-report-detail admin-report-highlight">
                <div className="admin-report-section-heading"><div><span className="admin-eyebrow">{t('المبيعات')}</span>
                  <h3>{t('المنتجات الأكثر مبيعاً')}</h3></div><span className="admin-report-period-chip">{t('هذا الشهر')}</span></div>
                {bestSellingProducts.length ? <div className="admin-report-highlight-layout">
                  <ReportDonut values={bestSellingProducts.map((product) => product.salesTotal)}
                    labels={bestSellingProducts.map((product) => product.productName)} valueUnit="₪"
                    centerValue={`${bestSellingProducts.reduce((total, product) => total + product.salesTotal, 0).toLocaleString(language)} ₪`}
                    centerLabel="مبيعات" label="توزيع المبيعات" />
                  <div className="admin-report-highlight-rows">{bestSellingProducts.map((product) => (
                  <div className="admin-report-product-row" key={`${product.productName}-${product.unit}`}>
                    <span className="admin-report-rank">{(bestSellingProducts.indexOf(product) + 1).toLocaleString(language)}</span>
                    <div className="admin-report-product-main"><strong>{product.productName}</strong>
                      <small>{product.quantitySold.toLocaleString(language)} {product.unit} · {product.bookingCount.toLocaleString(language)} فاتورة</small></div>
                    <strong className="admin-report-product-value">{product.salesTotal.toLocaleString(language)} ₪</strong>
                  </div>
                  ))}</div>
                </div> : <p className="admin-state">{t('لا توجد منتجات مباعة في هذا الشهر.')}</p>}
              </section>
              </div>
              <section className="admin-report-detail admin-report-invoices-section">
                <div className="admin-report-section-heading"><div><span className="admin-eyebrow">{t('سجل العمليات')}</span>
                  <h3>{t('الفواتير المكتملة')}</h3></div><span className="admin-report-period-chip">{bookingSales.length.toLocaleString(language)} {t('فاتورة')}</span></div>
                <p className="admin-report-invoice-hint">{t('اضغط على الفاتورة لعرض تفاصيل الخدمة والمنتجات.')}</p>
                {bookingSales.length ? <div className="admin-report-invoice-list">
                  {bookingSales.slice(invoicePage * 5, invoicePage * 5 + 5).map((booking) => (
                    <details className="admin-report-invoice" key={booking.bookingId}>
                      <summary className="admin-report-invoice-summary">
                        <span className="admin-report-invoice-customer"><strong>{booking.customerName}</strong>
                          <small>{new Date(booking.completedAt).toLocaleString(language)}</small></span>
                        <span className="admin-report-invoice-service">{booking.serviceName}
                          {booking.products.length > 0 && <small> + {booking.products.length.toLocaleString(language)} {t('منتجات')}</small>}</span>
                        <span className="admin-report-invoice-paid"><strong>{booking.amountPaid.toLocaleString(language)} ₪</strong>
                          <small>{t('من')} {booking.invoiceTotal.toLocaleString(language)} ₪</small></span>
                        <ChevronDown size={17} className="admin-report-invoice-chevron" />
                      </summary>
                      <div className="admin-report-invoice-details">
                        <div className="admin-report-product-row">
                          <strong>{booking.serviceName}</strong><span>{t('الخدمة')}</span>
                          <span>{booking.servicePrice.toLocaleString(language)} ₪</span>
                        </div>
                        {booking.products.map((product, index) => (
                          <div className="admin-report-product-row" key={`${product.productName}-${index}`}>
                            <strong>{product.productName}</strong>
                            <span>{product.quantity.toLocaleString(language)} {product.unit} × {product.unitPrice.toLocaleString(language)} ₪</span>
                            <strong>{product.lineTotal.toLocaleString(language)} ₪</strong>
                          </div>
                        ))}
                        <div className="admin-report-invoice-total">
                          <span>{t('الإجمالي')} {booking.invoiceTotal.toLocaleString(language)} ₪</span>
                          <span>{t('المقبوض')} {booking.amountPaid.toLocaleString(language)} ₪</span>
                          {booking.balance > 0 && <strong>{t('المتبقي')} {booking.balance.toLocaleString(language)} ₪</strong>}
                        </div>
                      </div>
                    </details>
                  ))}
                  {bookingSales.length > 5 && <div className="admin-report-pagination" aria-label={t('صفحات الفواتير')}>
                    <button className="admin-secondary-button" type="button" disabled={invoicePage === 0}
                      onClick={() => setInvoicePage((page) => Math.max(0, page - 1))}>
                      <ChevronRight size={15} /> {t('السابق')}
                    </button>
                    <span>{language === 'ar' ? 'صفحة' : 'Page'} {(invoicePage + 1).toLocaleString(language)} {t('من')} {Math.ceil(bookingSales.length / 5).toLocaleString(language)}</span>
                    <button className="admin-secondary-button" type="button"
                      disabled={(invoicePage + 1) * 5 >= bookingSales.length}
                      onClick={() => setInvoicePage((page) => Math.min(Math.ceil(bookingSales.length / 5) - 1, page + 1))}>
                      {t('التالي')} <ChevronLeft size={15} />
                    </button>
                  </div>}
                </div> : <p className="admin-state">{t('لا توجد فواتير مكتملة في هذا الشهر.')}</p>}
              </section>
            </div>
          )
        )}
      </section>
      {expenseOpen && createPortal(
        <div className="admin-booking-modal-backdrop admin-customer-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setExpenseOpen(false)
          }}>
          <section className="admin-booking-modal admin-customer-modal" role="dialog" aria-modal="true"
            aria-labelledby="create-expense-title">
            <div className="admin-panel-heading">
              <div><span className="admin-eyebrow">{t('المصروفات')}</span>
                <h2 id="create-expense-title">{t(editingExpense ? 'تعديل المصروف' : 'تسجيل مصروف')}</h2></div>
              <button className="admin-icon-button" type="button" aria-label={t('إغلاق')}
                onClick={() => setExpenseOpen(false)}><X size={17} /></button>
            </div>
            <form className="admin-form admin-expense-form" onSubmit={(event) => void createExpense(event)}>
              <label>{t('التصنيف')}<input name="category" maxLength={80} defaultValue={editingExpense?.category ?? ''} placeholder={t('إيجار، أدوات، فواتير...')} required /></label>
              <label>{t('البيان')}<input name="description" maxLength={500} defaultValue={editingExpense?.description ?? ''} required /></label>
              <label>{t('المبلغ (₪)')}<input name="amount" type="number" min="0.01" step="0.01" defaultValue={editingExpense?.amount ?? ''} required /></label>
              <label>{t('التاريخ')}<input name="expenseDate" type="date" defaultValue={editingExpense?.expenseDate ?? getShopDate()}
                onClick={(event) => event.currentTarget.showPicker?.()} required /></label>
              <label>{t('طريقة الدفع')}<select name="method" defaultValue={editingExpense?.method ?? 'CASH'}>{methods.map((method) =>
                <option key={method.value} value={method.value}>{t(method.label)}</option>)}</select></label>
              <label>{t('ملاحظات')}<input name="notes" maxLength={1000} defaultValue={editingExpense?.notes ?? ''} /></label>
              <button className="admin-primary-button" disabled={busy}>
                {busy ? t('جارٍ الحفظ...') : editingExpense ? t('حفظ التعديل') : t('حفظ المصروف')}
              </button>
            </form>
          </section>
        </div>,
        document.body,
      )}
      {addDebtOpen && createPortal(
        <div className="admin-booking-modal-backdrop admin-customer-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setAddDebtOpen(false)
          }}>
          <section className="admin-booking-modal admin-customer-modal" role="dialog" aria-modal="true"
            aria-labelledby="add-manual-debt-title">
            <div className="admin-panel-heading">
              <div><span className="admin-eyebrow">{t('الحسابات والديون')}</span>
                <h2 id="add-manual-debt-title">{t('إضافة دين يدوي')}</h2></div>
              <button className="admin-icon-button" type="button" aria-label={t('إغلاق')}
                onClick={() => setAddDebtOpen(false)}><X size={17} /></button>
            </div>
            <form className="admin-form admin-manual-debt-form" onSubmit={(event) => void addManualDebt(event)}>
              <label>{t('اسم العميل')}<input name="customerName" maxLength={150} required /></label>
              <label>{t('رقم الهاتف')}<input name="phoneNumber" type="tel" maxLength={32} required /></label>
              <label>{t('سبب الدين')}<input name="description" maxLength={500} placeholder={t('خدمة، منتج، أو سبب آخر')} required /></label>
              <label>{t('المبلغ (₪)')}<input name="amount" type="number" min="0.01" step="0.01" required /></label>
              <button className="admin-primary-button" disabled={busy}>
                {busy ? t('جارٍ الحفظ...') : t('إضافة الدين')}
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
            aria-labelledby="finance-customer-details-title">
            <div className="admin-panel-heading">
              <div><span className="admin-eyebrow">{t('كشف الحساب')}</span>
                <h2 id="finance-customer-details-title">{selectedCustomerDetails.customer.name}</h2></div>
              <button className="admin-icon-button" type="button" aria-label={t('إغلاق')}
                onClick={() => setSelectedCustomerDetails(null)}><X size={17} /></button>
            </div>
            <p className="admin-contact-line" dir="ltr">{selectedCustomerDetails.customer.phoneNumber || '—'}</p>
            <div className="admin-summary-chips">
              <span>{t('إجمالي المتبقي')} {selectedCustomerDetails.customer.outstandingDebt.toLocaleString(language)} ₪</span>
            </div>
            <h3 className="admin-subheading">{t('الحركات غير المسددة بالكامل')}</h3>
            {unpaidBookingDetails.map((booking) => (
              <article className="admin-customer-report-booking" key={booking.id}>
                <div className="admin-history-row">
                  <span>{booking.serviceName}</span><span>{t(bookingStatusNames[booking.status] ?? booking.status)}</span>
                  <strong>{t('الإجمالي')} {booking.totalAmount.toLocaleString(language)} ₪</strong>
                  <small>{new Date(booking.createdAt).toLocaleDateString(language)}</small>
                </div>
                {booking.products.map((product, index) => (
                  <p className="admin-customer-purchases" key={`${product.productName}-${index}`}>
                    {product.productName} · {product.quantity.toLocaleString(language)} {product.unit}
                    {' · '}{product.lineTotal.toLocaleString(language)} ₪
                  </p>
                ))}
                <div className="admin-history-row">
                  <span>{t('المدفوع')} {booking.paidAmount.toLocaleString(language)} ₪</span>
                  <strong>{t('المتبقي')} {booking.outstandingBalance.toLocaleString(language)} ₪</strong>
                </div>
                {selectedCustomerDetails.payments.filter((payment) => payment.bookingId === booking.id).map((payment) => (
                  <small className="admin-customer-purchases" key={payment.id}>
                    {t('دفعة')} {payment.amount.toLocaleString(language)} ₪ · {paymentMethodLabel(payment.method, t)}
                    {payment.notes ? ` · ${payment.notes}` : ''}
                  </small>
                ))}
              </article>
            ))}
            {unpaidManualDebts.map((debt) => (
              <article className="admin-customer-report-booking" key={debt.id}>
                <strong>{t('دين يدوي')} · {debt.description}</strong>
                <div className="admin-history-row">
                  <span>{t('المبلغ')} {debt.totalAmount.toLocaleString(language)} ₪</span>
                  <span>{t('المدفوع')} {debt.paidAmount.toLocaleString(language)} ₪</span>
                  <strong>{t('المتبقي')} {debt.outstandingBalance.toLocaleString(language)} ₪</strong>
                  <small>{new Date(debt.createdAt).toLocaleDateString(language)}</small>
                </div>
                {debt.payments.map((payment) => <small className="admin-customer-purchases" key={payment.id}>
                  {t('دفعة')} {payment.amount.toLocaleString(language)} ₪ · {paymentMethodLabel(payment.method, t)}
                  {payment.notes ? ` · ${payment.notes}` : ''}
                </small>)}
              </article>
            ))}
            {!unpaidBookingDetails.length && !unpaidManualDebts.length
              && <p className="admin-state">{t('لا توجد حركات عليها مبلغ متبقٍ.')}</p>}
          </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminFinancePage
