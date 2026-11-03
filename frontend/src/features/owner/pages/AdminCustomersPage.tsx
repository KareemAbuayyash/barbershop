import { useEffect, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { FileText, Pencil, Search, UserRound, X } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminCustomer, AdminCustomerDetails, AdminPage } from '../admin-api'

function AdminCustomersPage() {
  const [query, setQuery] = useState('')
  const [customerType, setCustomerType] = useState<'ALL' | 'ACCOUNTS' | 'GUESTS'>('ALL')
  const [customers, setCustomers] = useState<AdminPage<AdminCustomer> | null>(null)
  const [selected, setSelected] = useState<AdminCustomerDetails | null>(null)
  const [page, setPage] = useState(0)
  const [refreshKey, setRefreshKey] = useState(0)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(true)
  const [editName, setEditName] = useState('')
  const [editPhone, setEditPhone] = useState('')
  const [saving, setSaving] = useState(false)
  const [customerView, setCustomerView] = useState<'profile' | 'report'>('profile')
  const [editingCustomer, setEditingCustomer] = useState(false)

  useEffect(() => {
    let active = true
    const params = new URLSearchParams({ page: String(page), size: '20', type: customerType })
    if (query.trim()) params.set('q', query.trim())
    void apiRequest<AdminPage<AdminCustomer>>(`/api/admin/customers?${params}`)
      .then((result) => { if (active) setCustomers(result) })
      .catch((cause: unknown) => { if (active) setError(getErrorMessage(cause, 'تعذر تحميل العملاء.')) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [customerType, page, query, refreshKey])

  const customerTypeParam = (hasAccount: boolean) => `type=${hasAccount ? 'ACCOUNTS' : 'GUESTS'}`

  const showCustomer = async (customer: AdminCustomer) => {
    setError('')
    setNotice('')
    setCustomerView('profile')
    setEditingCustomer(false)
    try {
      const details = await apiRequest<AdminCustomerDetails>(
        `/api/admin/customers/${customer.id}?${customerTypeParam(customer.hasAccount)}`,
      )
      setSelected(details)
      setEditName(details.customer.name)
      setEditPhone(details.customer.phoneNumber ?? '')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل ملف العميل.'))
    }
  }

  const saveCustomer = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selected) return
    setSaving(true)
    setError('')
    setNotice('')
    try {
      const updated = await apiRequest<AdminCustomerDetails>(
        `/api/admin/customers/${selected.customer.id}?${customerTypeParam(selected.customer.hasAccount)}`, {
        method: 'PUT',
        body: JSON.stringify({ name: editName.trim(), phoneNumber: editPhone.trim() || null }),
      })
      setSelected(updated)
      setEditName(updated.customer.name)
      setEditPhone(updated.customer.phoneNumber ?? '')
      setRefreshKey((value) => value + 1)
      setNotice('تم حفظ بيانات العميل.')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ بيانات العميل.'))
    } finally { setSaving(false) }
  }

  const resetPassword = async (event: FormEvent<HTMLFormElement>, accountId: string) => {
    event.preventDefault()
    if (!selected) return
    const form = event.currentTarget
    const password = new FormData(form).get('newPassword')
    setSaving(true)
    setError('')
    setNotice('')
    try {
      await apiRequest(
        `/api/admin/customers/${selected.customer.id}/accounts/${accountId}/password?${customerTypeParam(selected.customer.hasAccount)}`, {
        method: 'PUT',
        body: JSON.stringify({ newPassword: String(password) }),
      })
      form.reset()
      setNotice('تم تغيير كلمة السر بنجاح.')
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تغيير كلمة مرور الحساب.'))
    } finally { setSaving(false) }
  }

  return (
    <div className="admin-page">
      <section className="admin-panel">
        <div className="admin-panel-heading">
          <div><span className="admin-eyebrow">العلاقات وسجل الزيارات</span><h2>دليل العملاء</h2></div>
          <span className="admin-result-count">{customers?.totalElements.toLocaleString('ar') ?? '—'} عميل</span>
        </div>
        <label className="admin-search">
          <Search size={17} aria-hidden="true" />
          <input value={query} maxLength={150} placeholder="ابحث باسم العميل أو رقم الهاتف"
            onChange={(event) => {
              setLoading(true)
              setError('')
              setQuery(event.target.value)
              setPage(0)
            }} />
        </label>
        <div className="admin-customer-filters" role="group" aria-label="تصفية العملاء">
          {([
            ['ALL', 'كل العملاء'],
            ['ACCOUNTS', 'أصحاب الحسابات'],
            ['GUESTS', 'الضيوف'],
          ] as const).map(([type, label]) => (
            <button key={type} type="button"
              className={customerType === type ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
              aria-pressed={customerType === type}
              onClick={() => {
                setLoading(true)
                setCustomerType(type)
                setPage(0)
              }}>{label}</button>
          ))}
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {loading ? <p className="admin-state" role="status">جارٍ تحميل العملاء...</p>
          : !customers?.content.length ? (
            <div className="admin-empty"><UserRound size={25} /><h3>لا يوجد عملاء بعد</h3>
              <p>يظهر العميل هنا بعد إنشاء حجز مرتبط ببياناته.</p></div>
          ) : (
            <>
              <div className="admin-customer-list">
                {customers.content.map((customer) => (
                  <article className="admin-customer-row" key={customer.id}>
                    <span className="admin-customer-avatar">{customer.name.slice(0, 1)}</span>
                    <span className="admin-customer-identity"><strong>{customer.name}</strong>
                      <small dir="ltr">{customer.phoneNumber || 'لا يوجد رقم هاتف'}</small>
                      <small>{customer.hasAccount ? 'حساب مسجل' : 'حجز كضيف'}</small></span>
                    <span><small>الزيارات</small><strong>{customer.visits.toLocaleString('ar')}</strong></span>
                    <span><small>المدفوع</small><strong>{customer.totalPaid.toLocaleString('ar')} ₪</strong></span>
                    <span><small>المتبقي</small><strong className={customer.outstandingDebt > 0 ? 'admin-debt-value' : ''}>
                      {customer.outstandingDebt.toLocaleString('ar')} ₪</strong></span>
                    <div className="admin-customer-actions">
                      <button type="button" className="admin-secondary-button"
                        onClick={() => void showCustomer(customer)}>ملف العميل</button>
                    </div>
                  </article>
                ))}
              </div>
              <div className="admin-pagination">
                <span>صفحة {(customers.page + 1).toLocaleString('ar')} من {Math.max(customers.totalPages, 1).toLocaleString('ar')}</span>
                <div><button type="button" disabled={page === 0} onClick={() => {
                  setLoading(true)
                  setPage((value) => Math.max(0, value - 1))
                }}>السابق</button>
                  <button type="button" disabled={page + 1 >= customers.totalPages} onClick={() => {
                    setLoading(true)
                    setPage((value) => value + 1)
                  }}>التالي</button></div>
              </div>
            </>
          )}
      </section>
      {selected && createPortal(
        <div className="admin-booking-modal-backdrop admin-customer-modal-backdrop" role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setSelected(null)
          }}>
        <section className="admin-booking-modal admin-customer-modal" role="dialog" aria-modal="true"
          aria-labelledby="customer-modal-title" dir="rtl">
          <div className="admin-panel-heading">
            <div><span className="admin-eyebrow">إدارة العملاء</span><h2 id="customer-modal-title">{selected.customer.name}</h2></div>
            <button type="button" className="admin-icon-button" aria-label="إغلاق" onClick={() => setSelected(null)}><X size={17} /></button>
          </div>
          <p className="admin-contact-line" dir="ltr">{selected.customer.phoneNumber || '—'}</p>
          {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
          {notice && <p className="admin-message" role="status">{notice}</p>}
          <div className="admin-customer-tabs" role="tablist" aria-label="خيارات العميل">
            <button type="button" role="tab" aria-selected={customerView === 'profile'}
              className={customerView === 'profile' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
              onClick={() => setCustomerView('profile')}><UserRound size={15} /> ملف الحساب</button>
            <button type="button" role="tab" aria-selected={customerView === 'report'}
              className={customerView === 'report' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
              onClick={() => { setCustomerView('report'); setEditingCustomer(false) }}><FileText size={15} /> تقرير العميل</button>
          </div>
          <div className="admin-summary-chips">
            <span>{selected.customer.visits.toLocaleString('ar')} زيارة</span>
            <span>المدفوع {selected.customer.totalPaid.toLocaleString('ar')} ₪</span>
            <span>الرصيد {selected.customer.outstandingDebt.toLocaleString('ar')} ₪</span>
          </div>
          {customerView === 'profile' ? (
            <>
              <div className="admin-customer-modal-actions">
                <h3>بيانات العميل</h3>
                <button type="button" className="admin-secondary-button"
                  onClick={() => setEditingCustomer((value) => !value)}><Pencil size={15} />
                  {editingCustomer ? 'إلغاء التعديل' : 'تعديل البيانات'}</button>
              </div>
              {editingCustomer && <form className="admin-form admin-customer-edit" onSubmit={(event) => void saveCustomer(event)}>
                <label>الاسم<input value={editName} maxLength={150} required
                  onChange={(event) => setEditName(event.target.value)} /></label>
                <label>رقم الهاتف<input value={editPhone} type="tel" maxLength={32}
                  onChange={(event) => setEditPhone(event.target.value)} /></label>
                <button className="admin-primary-button" disabled={saving}>
                  {saving ? 'جارٍ الحفظ...' : 'حفظ بيانات العميل'}
                </button>
              </form>}
              <h3 className="admin-subheading">حسابات الدخول</h3>
              {selected.accounts.length ? selected.accounts.map((account) => (
                <form className="admin-customer-account" key={account.id}
                  onSubmit={(event) => void resetPassword(event, account.id)}>
                  <div><strong>{account.displayName}</strong><small dir="ltr">{account.username}</small></div>
                  <label>كلمة مرور جديدة
                    <input name="newPassword" type="password" autoComplete="new-password"
                      minLength={8} maxLength={72} required />
                  </label>
                  <button type="submit" className="admin-secondary-button" disabled={saving}>
                    تغيير كلمة المرور
                  </button>
                </form>
              )) : <p className="admin-state">لا يوجد لهذا العميل حساب دخول مسجل.</p>}
            </>
          ) : (
            <>
              <h3 className="admin-subheading">سجل الزيارات والحجوزات</h3>
              {selected.bookings.length ? selected.bookings.map((booking) => (
                <article className="admin-customer-report-booking" key={booking.id}>
                  <div className="admin-history-row">
                    <span>{booking.serviceName}</span><span>{booking.status}</span>
                    <strong>{booking.servicePrice.toLocaleString('ar')} ₪</strong>
                    <small>{new Date(booking.createdAt).toLocaleDateString('ar')}</small>
                  </div>
                  {booking.products.length > 0 && <p className="admin-customer-purchases">
                    المنتجات: {booking.products.map((product) =>
                      `${product.productName} (${product.quantity.toLocaleString('ar')} ${product.unit}) · ${product.lineTotal.toLocaleString('ar')} ₪`)
                      .join('، ')}
                  </p>}
                </article>
              )) : <p className="admin-state">لا يوجد سجل حجوزات.</p>}
              <h3 className="admin-subheading">سجل الدفعات</h3>
              {selected.payments.length ? selected.payments.map((payment) => (
                <div className="admin-history-row" key={payment.id}>
                  <span>{payment.method}</span><span>{payment.notes || 'دفعة'}</span>
                  <strong>{payment.amount.toLocaleString('ar')} ₪</strong>
                  <small>{new Date(payment.paidAt).toLocaleDateString('ar')}</small>
                </div>
              )) : <p className="admin-state">لا توجد دفعات مسجلة.</p>}
            </>
          )}
        </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminCustomersPage
