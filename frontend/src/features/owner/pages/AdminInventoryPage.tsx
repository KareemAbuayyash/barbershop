import { useEffect, useState, type FormEvent } from 'react'
import { AlertTriangle, Package, Plus, TrendingDown, TrendingUp } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminInventoryMovement, AdminProduct } from '../admin-api'

const movementNames: Record<AdminInventoryMovement['movementType'], string> = {
  PURCHASE: 'شراء / توريد',
  CONSUMPTION: 'استهلاك',
  SALE: 'بيع',
  ADJUSTMENT: 'إضافة كمية',
}

function AdminInventoryPage() {
  const [products, setProducts] = useState<AdminProduct[]>([])
  const [history, setHistory] = useState<Record<string, AdminInventoryMovement[]>>({})
  const [expanded, setExpanded] = useState('')
  const [editing, setEditing] = useState<AdminProduct | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)

  const load = async () => {
    try {
      setProducts(await apiRequest<AdminProduct[]>('/api/admin/inventory/products'))
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل المخزون.'))
    }
  }
  useEffect(() => {
    let active = true
    void apiRequest<AdminProduct[]>('/api/admin/inventory/products')
      .then((data) => { if (active) setProducts(data) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل المخزون.'))
      })
    return () => { active = false }
  }, [])

  const createProduct = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    setBusy(true)
    setError('')
    try {
      await apiRequest('/api/admin/inventory/products', {
        method: 'POST',
        body: JSON.stringify({
          name: String(formData.get('name')).trim(),
          unit: String(formData.get('unit')).trim(),
          initialQuantity: Number(formData.get('initialQuantity')),
          lowStockThreshold: Number(formData.get('lowStockThreshold')),
        }),
      })
      form.reset()
      setNotice('تمت إضافة المنتج.')
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر إضافة المنتج.'))
    } finally { setBusy(false) }
  }

  const recordMovement = async (event: FormEvent<HTMLFormElement>, product: AdminProduct) => {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true)
    setError('')
    try {
      await apiRequest(`/api/admin/inventory/products/${product.id}/movements`, {
        method: 'POST',
        body: JSON.stringify({
          movementType: String(data.get('movementType')),
          quantity: Number(data.get('quantity')),
          unitCost: data.get('unitCost') ? Number(data.get('unitCost')) : null,
          notes: String(data.get('notes')).trim() || null,
        }),
      })
      form.reset()
      setNotice('تم تحديث الرصيد وحفظ حركة المخزون.')
      await load()
      await loadHistory(product.id)
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تسجيل حركة المخزون.'))
    } finally { setBusy(false) }
  }

  const loadHistory = async (id: string) => {
    const movements = await apiRequest<AdminInventoryMovement[]>(`/api/admin/inventory/products/${id}/movements`)
    setHistory((value) => ({ ...value, [id]: movements }))
  }

  const updateProduct = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!editing) return
    const form = event.currentTarget
    const data = new FormData(form)
    setBusy(true)
    setError('')
    try {
      await apiRequest(`/api/admin/inventory/products/${editing.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          name: String(data.get('name')).trim(),
          unit: String(data.get('unit')).trim(),
          lowStockThreshold: Number(data.get('lowStockThreshold')),
          active: data.get('active') === 'on',
        }),
      })
      setEditing(null)
      setNotice('تم تحديث بيانات المنتج.')
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث المنتج.'))
    } finally { setBusy(false) }
  }

  return (
    <div className="admin-page admin-two-column">
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">الرصيد والحركات</span><h2>المخزون</h2></div>
          <span className="admin-result-count">{products.length.toLocaleString('ar')} منتج</span></div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {!products.length ? <div className="admin-empty"><Package size={24} /><h3>لا توجد منتجات مسجلة</h3></div> : (
          <div className="admin-inventory-list">
            {products.map((product) => {
              const low = product.active && product.quantity <= product.lowStockThreshold
              return (
                <article className={`admin-inventory-card${low ? ' admin-inventory-card--low' : ''}`} key={product.id}>
                  <div className="admin-inventory-heading">
                    <span className="admin-stat-icon">{low ? <AlertTriangle size={18} /> : <Package size={18} />}</span>
                    <div><strong>{product.name}</strong><small>{product.active ? 'نشط' : 'موقوف'}</small></div>
                    <strong className="admin-stock-value">{product.quantity} {product.unit}</strong>
                  </div>
                  <p className={low ? 'admin-low-stock' : 'admin-stock-threshold'}>
                    {low ? 'المخزون منخفض' : 'حد التنبيه'} · {product.lowStockThreshold} {product.unit}
                  </p>
                  <div className="admin-inline-actions">
                    <button className="admin-secondary-button" type="button" onClick={() => {
                      const next = expanded === product.id ? '' : product.id
                      setExpanded(next)
                      if (next && !history[next]) void loadHistory(next).catch((cause: unknown) =>
                        setError(getErrorMessage(cause, 'تعذر تحميل سجل الحركات.')))
                    }}>تسجيل حركة / السجل</button>
                    <button className="admin-secondary-button" type="button" onClick={() => setEditing(product)}>تعديل المنتج</button>
                  </div>
                  {expanded === product.id && (
                    <div className="admin-inventory-expanded">
                      <form className="admin-payment-form" onSubmit={(event) => void recordMovement(event, product)}>
                        <label>نوع الحركة<select name="movementType">
                          {Object.entries(movementNames).map(([value, name]) =>
                            <option value={value} key={value}>{name}</option>)}
                        </select></label>
                        <label>الكمية<input name="quantity" type="number" min="0.001" step="0.001" required /></label>
                        <label>تكلفة الوحدة (اختياري)<input name="unitCost" type="number" min="0" step="0.01" /></label>
                        <label>ملاحظة<input name="notes" maxLength={1000} /></label>
                        <button className="admin-primary-button" disabled={busy}>حفظ الحركة</button>
                      </form>
                      <h3 className="admin-subheading">آخر الحركات</h3>
                      {history[product.id]?.length ? history[product.id].map((movement) => (
                        <div className="admin-history-row" key={movement.id}>
                          <span>{movementNames[movement.movementType]}</span>
                          <strong>{movement.quantity} {product.unit}</strong>
                          {movement.unitCost && <span>{movement.unitCost.toLocaleString('ar')} ₪</span>}
                          <small>{new Date(movement.createdAt).toLocaleString('ar')}</small>
                        </div>
                      )) : <p className="admin-state">لا توجد حركات مسجلة.</p>}
                    </div>
                  )}
                </article>
              )
            })}
          </div>
        )}
      </section>
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">إضافة منتج</span><h2>منتج جديد</h2></div><Plus size={20} /></div>
        <form className="admin-form" onSubmit={(event) => void createProduct(event)}>
          <label>اسم المنتج<input name="name" maxLength={150} required /></label>
          <label>وحدة القياس<input name="unit" maxLength={30} placeholder="قطعة، عبوة، مل..." required /></label>
          <label>الرصيد الافتتاحي<input name="initialQuantity" type="number" min="0" step="0.001" defaultValue="0" required /></label>
          <label>حد تنبيه المخزون المنخفض<input name="lowStockThreshold" type="number" min="0" step="0.001" defaultValue="1" required /></label>
          <button className="admin-primary-button" disabled={busy}><TrendingUp size={17} /> إضافة المنتج</button>
        </form>
        {editing && (
          <form className="admin-form admin-edit-product" onSubmit={(event) => void updateProduct(event)}>
            <h3>تعديل {editing.name}</h3>
            <label>اسم المنتج<input name="name" defaultValue={editing.name} maxLength={150} required /></label>
            <label>الوحدة<input name="unit" defaultValue={editing.unit} maxLength={30} required /></label>
            <label>حد تنبيه المخزون<input name="lowStockThreshold" type="number" min="0" step="0.001"
              defaultValue={editing.lowStockThreshold} required /></label>
            <label className="admin-checkbox"><input type="checkbox" name="active" defaultChecked={editing.active} /> المنتج نشط</label>
            <button className="admin-primary-button" disabled={busy}>حفظ التعديل</button>
            <button className="admin-secondary-button" type="button" onClick={() => setEditing(null)}>إلغاء</button>
          </form>
        )}
        <p className="admin-form-note"><TrendingDown size={15} /> كل حركة تحفظ في سجل منفصل ولا يمكن أن تجعل الرصيد سالباً.</p>
      </section>
    </div>
  )
}

export default AdminInventoryPage
