import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { AlertTriangle, Camera, Images, Package, Plus, ScrollText, Trash2, TrendingDown, TrendingUp, X } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import type { AdminInventoryMovement, AdminProduct } from '../admin-api'

const movementNames: Record<AdminInventoryMovement['movementType'], string> = {
  PURCHASE: 'شراء / توريد',
  CONSUMPTION: 'استهلاك',
  SALE: 'بيع',
  ADJUSTMENT: 'إضافة كمية',
}

function ProductImagePicker() {
  const galleryInput = useRef<HTMLInputElement>(null)
  const cameraInput = useRef<HTMLInputElement>(null)
  const [sourcePickerOpen, setSourcePickerOpen] = useState(false)
  const [filename, setFilename] = useState('')

  const chooseSource = (source: 'gallery' | 'camera') => {
    setSourcePickerOpen(false)
    window.setTimeout(() => {
      const input = source === 'camera' ? cameraInput.current : galleryInput.current
      input?.click()
    }, 0)
  }

  return <>
    <div className="admin-image-picker-control">
      <span>صورة المنتج (اختياري)</span>
      <button className="admin-secondary-button" type="button" onClick={() => setSourcePickerOpen(true)}>
        <Images size={16} /> {filename || 'اختيار صورة'}
      </button>
      <input ref={galleryInput} name="imageGallery" type="file" accept="image/png,image/jpeg,image/webp"
        hidden onChange={(event) => setFilename(event.target.files?.[0]?.name ?? '')} />
      <input ref={cameraInput} name="imageCamera" type="file" accept="image/png,image/jpeg,image/webp"
        capture="environment" hidden onChange={(event) => setFilename(event.target.files?.[0]?.name ?? '')} />
    </div>
    {sourcePickerOpen && createPortal(
      <div className="admin-image-source-backdrop" role="presentation"
        onMouseDown={(event) => { if (event.target === event.currentTarget) setSourcePickerOpen(false) }}>
        <section className="admin-image-source-dialog" role="dialog" aria-modal="true"
          aria-labelledby="image-source-title" dir="rtl">
          <div className="admin-booking-modal-heading">
            <h2 id="image-source-title">اختيار مصدر الصورة</h2>
            <button type="button" className="admin-icon-button" aria-label="إغلاق"
              onClick={() => setSourcePickerOpen(false)}><X size={17} /></button>
          </div>
          <button className="admin-secondary-button" type="button" onClick={() => chooseSource('camera')}>
            <Camera size={17} /> التقاط صورة بالكاميرا
          </button>
          <button className="admin-secondary-button" type="button" onClick={() => chooseSource('gallery')}>
            <Images size={17} /> اختيار من الاستديو
          </button>
        </section>
      </div>,
      document.body,
    )}
  </>
}

function selectedProductImage(data: FormData): File | null {
  for (const field of ['imageCamera', 'imageGallery']) {
    const image = data.get(field)
    if (image instanceof File && image.size > 0) return image
  }
  return null
}

async function compressProductImage(image: File): Promise<File> {
  const maxBytes = 1.8 * 1024 * 1024
  if (image.size <= maxBytes) return image

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(image)
  } catch {
    throw new Error('تعذر فتح الصورة لضغطها. جرّب صورة بصيغة JPG أو PNG.')
  }

  try {
    let scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height))
    for (let attempt = 0; attempt < 6; attempt += 1) {
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(bitmap.width * scale))
      canvas.height = Math.max(1, Math.round(bitmap.height * scale))
      const context = canvas.getContext('2d')
      if (!context) throw new Error('تعذر تجهيز الصورة. جرّب مرة أخرى.')
      context.fillStyle = '#fff'
      context.fillRect(0, 0, canvas.width, canvas.height)
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)

      const quality = Math.max(0.52, 0.84 - attempt * 0.07)
      const blob = await new Promise<Blob>((resolve, reject) => {
        canvas.toBlob((result) => result ? resolve(result) : reject(new Error('تعذر ضغط الصورة.')),
          'image/jpeg', quality)
      })
      if (blob.size <= maxBytes) {
        const name = image.name.replace(/\.[^.]+$/, '') || 'product-image'
        return new File([blob], `${name}.jpg`, { type: 'image/jpeg', lastModified: Date.now() })
      }
      scale *= 0.82
    }
    throw new Error('الصورة كبيرة جدًا. جرّب صورة أصغر.')
  } finally {
    bitmap.close()
  }
}

function AdminInventoryPage() {
  const [products, setProducts] = useState<AdminProduct[]>([])
  const [history, setHistory] = useState<Record<string, AdminInventoryMovement[]>>({})
  const [movementTypes, setMovementTypes] = useState<Record<string, AdminInventoryMovement['movementType']>>({})
  const [movementDialog, setMovementDialog] = useState<{
    product: AdminProduct
    view: 'record' | 'history'
  } | null>(null)
  const [editing, setEditing] = useState<AdminProduct | null>(null)
  const [createModalOpen, setCreateModalOpen] = useState(false)
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
      const request = new FormData()
      request.append('product', new Blob([JSON.stringify({
        name: String(formData.get('name')).trim(),
        unit: String(formData.get('unit')).trim(),
        availableQuantity: Number(formData.get('availableQuantity')),
        lowStockThreshold: Number(formData.get('lowStockThreshold')),
        purchasePrice: Number(formData.get('purchasePrice')),
        salePrice: Number(formData.get('salePrice')),
      })], { type: 'application/json' }))
      const image = selectedProductImage(formData)
      if (image) request.append('image', await compressProductImage(image))
      await apiRequest('/api/admin/inventory/products', {
        method: 'POST',
        body: request,
      })
      form.reset()
      setNotice('تمت إضافة المنتج.')
      setCreateModalOpen(false)
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
          unitPrice: data.get('unitPrice') ? Number(data.get('unitPrice')) : null,
          notes: String(data.get('notes')).trim() || null,
        }),
      })
      form.reset()
      setMovementTypes((types) => ({ ...types, [product.id]: 'PURCHASE' }))
      setNotice('تم تحديث الرصيد وحفظ حركة المخزون.')
      await load()
      await loadHistory(product.id)
      setMovementDialog({ product, view: 'history' })
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
      const request = new FormData()
      request.append('product', new Blob([JSON.stringify({
        name: String(data.get('name')).trim(),
        unit: String(data.get('unit')).trim(),
        lowStockThreshold: Number(data.get('lowStockThreshold')),
        purchasePrice: Number(data.get('purchasePrice')),
        salePrice: Number(data.get('salePrice')),
        active: data.get('active') === 'on',
      })], { type: 'application/json' }))
      const image = selectedProductImage(data)
      if (image) request.append('image', await compressProductImage(image))
      await apiRequest(`/api/admin/inventory/products/${editing.id}`, {
        method: 'PUT',
        body: request,
      })
      setEditing(null)
      setNotice('تم تحديث بيانات المنتج.')
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحديث المنتج.'))
    } finally { setBusy(false) }
  }

  const deleteProduct = async (product: AdminProduct) => {
    if (!window.confirm(`حذف المنتج «${product.name}»؟ لا يمكن حذف منتج لديه رصيد أو سجل حركات.`)) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest<void>(`/api/admin/inventory/products/${product.id}`, { method: 'DELETE' })
      setNotice('تم حذف المنتج.')
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حذف المنتج. إذا كان له سجل حركات، أوقفه من تعديل المنتج.'))
    } finally { setBusy(false) }
  }

  const openMovementDialog = (product: AdminProduct, view: 'record' | 'history') => {
    setMovementDialog({ product, view })
    setError('')
    if (view === 'history' && !history[product.id]) {
      void loadHistory(product.id).catch((cause: unknown) =>
        setError(getErrorMessage(cause, 'تعذر تحميل سجل الحركات.')))
    }
  }

  return (
    <div className="admin-page admin-inventory-page">
      <div className="admin-inventory-toolbar">
        <div><span className="admin-eyebrow">الرصيد والحركات</span><h2>المخزون</h2></div>
        <button className="admin-secondary-button" type="button" onClick={() => setCreateModalOpen(true)}>
          <Plus size={16} /> إضافة منتج
        </button>
      </div>
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
                    <span className={product.imageUrl ? 'admin-inventory-product-image' : 'admin-stat-icon'}>{product.imageUrl
                      ? <img src={product.imageUrl} alt="" width="76" height="76" />
                      : low ? <AlertTriangle size={18} /> : <Package size={18} />}</span>
                    <div><strong>{product.name}</strong><small>{product.active ? 'نشط' : 'موقوف'}</small></div>
                    <strong className="admin-stock-value">{product.quantity} {product.unit}</strong>
                  </div>
                  <p className="admin-stock-threshold">
                    شراء {product.purchasePrice.toLocaleString('ar')} ₪ · بيع {product.salePrice.toLocaleString('ar')} ₪
                  </p>
                  <p className={low ? 'admin-low-stock' : 'admin-stock-threshold'}>
                    {low ? 'المخزون منخفض' : 'حد التنبيه'} · {product.lowStockThreshold} {product.unit}
                  </p>
                  <div className="admin-inline-actions">
                    <button className="admin-secondary-button" type="button"
                      onClick={() => openMovementDialog(product, 'record')}><Plus size={15} /> تسجيل حركة</button>
                    <button className="admin-secondary-button" type="button"
                      onClick={() => openMovementDialog(product, 'history')}><ScrollText size={15} /> السجل</button>
                    <button className="admin-secondary-button" type="button" onClick={() => setEditing(product)}>تعديل المنتج</button>
                    <button className="admin-secondary-button admin-delete-product-button" type="button"
                      disabled={busy} onClick={() => void deleteProduct(product)}><Trash2 size={15} /> حذف المنتج</button>
                  </div>
                  {movementDialog?.product.id === product.id && createPortal(
                    <div className="admin-booking-modal-backdrop" role="presentation"
                      onMouseDown={(event) => {
                        if (event.target === event.currentTarget && !busy) setMovementDialog(null)
                      }}>
                      <section className="admin-booking-modal admin-inventory-movement-modal" role="dialog" aria-modal="true"
                        aria-labelledby="inventory-movement-title" dir="rtl">
                        <div className="admin-booking-modal-heading">
                          <div><span className="admin-eyebrow">المخزون والحركات</span>
                            <h2 id="inventory-movement-title">{product.name}</h2></div>
                          <button type="button" className="admin-icon-button" aria-label="إغلاق"
                            onClick={() => setMovementDialog(null)} disabled={busy}><X size={17} /></button>
                        </div>
                        <div className="admin-inventory-modal-tabs">
                          <button type="button" className={movementDialog.view === 'record' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
                            onClick={() => setMovementDialog({ product, view: 'record' })}>تسجيل حركة</button>
                          <button type="button" className={movementDialog.view === 'history' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
                            onClick={() => openMovementDialog(product, 'history')}>سجل الحركات</button>
                        </div>
                        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
                        {movementDialog.view === 'record' ? (
                      <form className="admin-payment-form" onSubmit={(event) => void recordMovement(event, product)}>
                        <label>نوع الحركة<select name="movementType" value={movementTypes[product.id] ?? 'PURCHASE'}
                          onChange={(event) => setMovementTypes((types) => ({
                            ...types, [product.id]: event.target.value as AdminInventoryMovement['movementType'],
                          }))}>
                          {Object.entries(movementNames).map(([value, name]) =>
                            <option value={value} key={value}>{name}</option>)}
                        </select></label>
                        <label>الكمية<input name="quantity" type="number" min="0.001" step="0.001" required /></label>
                        <label>{(movementTypes[product.id] ?? 'PURCHASE') === 'PURCHASE'
                          ? 'تكلفة الشراء للوحدة' : 'تكلفة الوحدة (اختياري)'}
                          <input name="unitCost" type="number" min="0" step="0.01"
                            required={(movementTypes[product.id] ?? 'PURCHASE') === 'PURCHASE'} />
                        </label>
                        {(movementTypes[product.id] ?? 'PURCHASE') === 'SALE' && (
                          <label>سعر البيع للوحدة (اختياري)
                            <input name="unitPrice" type="number" min="0" step="0.01" />
                          </label>
                        )}
                        <label>ملاحظة<input name="notes" maxLength={1000} /></label>
                        <button className="admin-primary-button" disabled={busy}>{busy ? 'جارٍ الحفظ...' : 'حفظ الحركة'}</button>
                      </form>
                        ) : <>
                      <h3 className="admin-subheading">آخر الحركات</h3>
                      {history[product.id]?.length ? history[product.id].map((movement) => (
                        <div className="admin-history-row" key={movement.id}>
                          <span>{movementNames[movement.movementType]}</span>
                          <strong>{movement.quantity.toLocaleString('ar')} {product.unit}</strong>
                          {movement.movementType === 'SALE' && (movement.unitPrice !== null
                            ? <span>سعر الوحدة {movement.unitPrice.toLocaleString('ar')} ₪ · الإجمالي {(movement.lineTotal ?? movement.unitPrice * movement.quantity).toLocaleString('ar')} ₪</span>
                            : <span>سعر البيع غير مسجل لهذه الحركة</span>)}
                          {movement.movementType !== 'SALE' && movement.unitCost !== null
                            && <span>تكلفة الوحدة {movement.unitCost.toLocaleString('ar')} ₪</span>}
                          <small>{new Date(movement.createdAt).toLocaleString('ar')}</small>
                          {movement.notes && <small>{movement.notes}</small>}
                        </div>
                      )) : <p className="admin-state">لا توجد حركات مسجلة.</p>}
                        </>}
                      </section>
                    </div>,
                    document.body,
                  )}
                </article>
              )
            })}
          </div>
        )}
      </section>
      {createModalOpen && createPortal(
        <div className="admin-booking-modal-backdrop" role="presentation"
          onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) setCreateModalOpen(false) }}>
          <section className="admin-booking-modal admin-product-create-modal" role="dialog" aria-modal="true"
            aria-labelledby="inventory-create-title" dir="rtl">
            <div className="admin-booking-modal-heading">
              <div><span className="admin-eyebrow">إضافة منتج</span><h2 id="inventory-create-title">منتج جديد</h2></div>
              <button type="button" className="admin-icon-button" aria-label="إغلاق"
                onClick={() => setCreateModalOpen(false)} disabled={busy}><X size={17} /></button>
            </div>
            <form className="admin-form" onSubmit={(event) => void createProduct(event)}>
              <label>اسم المنتج<input name="name" maxLength={150} required /></label>
              <label>وحدة القياس<input name="unit" maxLength={30} placeholder="قطعة، عبوة، مل..." required /></label>
              <label>الكمية المتوفرة<input name="availableQuantity" type="number" min="0" step="0.001" defaultValue="0" required /></label>
              <label>سعر الشراء للوحدة (₪)<input name="purchasePrice" type="number" min="0" step="0.01" required /></label>
              <label>سعر البيع للوحدة (₪)<input name="salePrice" type="number" min="0.01" step="0.01" required /></label>
              <label>حد تنبيه المخزون المنخفض<input name="lowStockThreshold" type="number" min="0" step="1" defaultValue="1" required /></label>
              <ProductImagePicker />
              {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
              <button className="admin-primary-button" disabled={busy}>
                <TrendingUp size={17} /> {busy ? 'جارٍ الإضافة...' : 'إضافة المنتج'}
              </button>
            </form>
            <p className="admin-form-note"><TrendingDown size={15} /> كل حركة تحفظ في سجل منفصل ولا يمكن أن تجعل الرصيد سالباً.</p>
          </section>
        </div>,
        document.body,
      )}
      {editing && createPortal(
        <div className="admin-booking-modal-backdrop" role="presentation"
          onMouseDown={(event) => { if (event.target === event.currentTarget) setEditing(null) }}>
          <section className="admin-booking-modal admin-inventory-edit-modal" role="dialog" aria-modal="true"
            aria-labelledby="inventory-edit-title" dir="rtl">
            <div className="admin-booking-modal-heading">
              <div><span className="admin-eyebrow">إدارة المخزون</span>
                <h2 id="inventory-edit-title">تعديل {editing.name}</h2></div>
              <button type="button" className="admin-icon-button" aria-label="إغلاق" onClick={() => setEditing(null)}>
                <X size={17} />
              </button>
            </div>
            {editing.imageUrl && <img className="admin-product-preview" src={editing.imageUrl} alt={editing.name} />}
            <form className="admin-form" onSubmit={(event) => void updateProduct(event)}>
              <label>اسم المنتج<input name="name" defaultValue={editing.name} maxLength={150} required /></label>
              <label>الوحدة<input name="unit" defaultValue={editing.unit} maxLength={30} required /></label>
              <label>الكمية المتوفرة<input value={`${editing.quantity} ${editing.unit}`} readOnly /></label>
              <label>سعر الشراء للوحدة (₪)<input name="purchasePrice" type="number" min="0" step="0.01"
                defaultValue={editing.purchasePrice} required /></label>
              <label>سعر البيع للوحدة (₪)<input name="salePrice" type="number" min="0.01" step="0.01"
                defaultValue={editing.salePrice} required /></label>
              <label>حد تنبيه المخزون<input name="lowStockThreshold" type="number" min="0" step="1"
                defaultValue={editing.lowStockThreshold} required /></label>
              <ProductImagePicker />
              <label className="admin-checkbox"><input type="checkbox" name="active" defaultChecked={editing.active} /> المنتج نشط</label>
              <button className="admin-primary-button" disabled={busy}>حفظ التعديل</button>
              <button className="admin-secondary-button" type="button" onClick={() => setEditing(null)}>إلغاء</button>
            </form>
          </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminInventoryPage
