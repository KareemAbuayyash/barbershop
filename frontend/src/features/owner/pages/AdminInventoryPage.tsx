import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { AlertTriangle, Camera, Images, Package, Plus, ScrollText, Trash2, TrendingDown, TrendingUp, X } from 'lucide-react'
import { apiRequest, getErrorMessage } from '../../../lib/api'
import { useI18n } from '../../../lib/i18n'
import type { AdminInventoryMovement, AdminProduct } from '../admin-api'

const movementNames: Record<AdminInventoryMovement['movementType'], string> = {
  PURCHASE: 'شراء / توريد',
  CONSUMPTION: 'استهلاك',
  SALE: 'بيع',
  ADJUSTMENT: 'إضافة كمية',
}

function ProductImagePicker() {
  const { t } = useI18n()
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
      <span>{t('صورة المنتج (اختياري)')}</span>
      <button className="admin-secondary-button" type="button" onClick={() => setSourcePickerOpen(true)}>
        <Images size={16} /> {filename || t('اختيار صورة')}
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
          aria-labelledby="image-source-title">
          <div className="admin-booking-modal-heading">
            <h2 id="image-source-title">{t('اختيار مصدر الصورة')}</h2>
            <button type="button" className="admin-icon-button" aria-label={t('إغلاق')}
              onClick={() => setSourcePickerOpen(false)}><X size={17} /></button>
          </div>
          <button className="admin-secondary-button" type="button" onClick={() => chooseSource('camera')}>
            <Camera size={17} /> {t('التقاط صورة بالكاميرا')}
          </button>
          <button className="admin-secondary-button" type="button" onClick={() => chooseSource('gallery')}>
            <Images size={17} /> {t('اختيار من الاستديو')}
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
  const { t, language } = useI18n()
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
      setError(t(getErrorMessage(cause, 'تعذر تحميل المخزون.')))
    }
  }
  useEffect(() => {
    let active = true
    void apiRequest<AdminProduct[]>('/api/admin/inventory/products')
      .then((data) => { if (active) setProducts(data) })
      .catch((cause: unknown) => {
        if (active) setError(t(getErrorMessage(cause, 'تعذر تحميل المخزون.')))
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
      setNotice(t('تمت إضافة المنتج.'))
      setCreateModalOpen(false)
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر إضافة المنتج.')))
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
      setNotice(t('تم تحديث الرصيد وحفظ حركة المخزون.'))
      await load()
      await loadHistory(product.id)
      setMovementDialog({ product, view: 'history' })
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تسجيل حركة المخزون.')))
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
      setNotice(t('تم تحديث بيانات المنتج.'))
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تحديث المنتج.')))
    } finally { setBusy(false) }
  }

  const deleteProduct = async (product: AdminProduct) => {
    if (!window.confirm(`${t('حذف المنتج')} «${product.name}»؟ ${t('لا يمكن حذف منتج لديه رصيد أو سجل حركات.')}`)) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await apiRequest<void>(`/api/admin/inventory/products/${product.id}`, { method: 'DELETE' })
      setNotice(t('تم حذف المنتج.'))
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر حذف المنتج. إذا كان له سجل حركات، أوقفه من تعديل المنتج.')))
    } finally { setBusy(false) }
  }

  const openMovementDialog = (product: AdminProduct, view: 'record' | 'history') => {
    setMovementDialog({ product, view })
    setError('')
    if (view === 'history' && !history[product.id]) {
      void loadHistory(product.id).catch((cause: unknown) =>
        setError(t(getErrorMessage(cause, 'تعذر تحميل سجل الحركات.'))))
    }
  }

  return (
    <div className="admin-page admin-inventory-page">
      <div className="admin-inventory-toolbar">
        <div><span className="admin-eyebrow">{t('الرصيد والحركات')}</span><h2>{t('المخزون')}</h2></div>
        <button className="admin-secondary-button" type="button" onClick={() => setCreateModalOpen(true)}>
          <Plus size={16} /> {t('إضافة منتج')}
        </button>
      </div>
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">{t('الرصيد والحركات')}</span><h2>{t('المخزون')}</h2></div>
          <span className="admin-result-count">{products.length.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} {t('منتج')}</span></div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {!products.length ? <div className="admin-empty"><Package size={24} /><h3>{t('لا توجد منتجات مسجلة')}</h3></div> : (
          <div className="admin-inventory-list">
            {products.map((product) => {
              const low = product.active && product.quantity <= product.lowStockThreshold
              return (
                <article className={`admin-inventory-card${low ? ' admin-inventory-card--low' : ''}`} key={product.id}>
                  <div className="admin-inventory-heading">
                    <span className={product.imageUrl ? 'admin-inventory-product-image' : 'admin-stat-icon'}>{product.imageUrl
                      ? <img src={product.imageUrl} alt="" width="76" height="76" />
                      : low ? <AlertTriangle size={18} /> : <Package size={18} />}</span>
                    <div><strong>{product.name}</strong><small>{t(product.active ? 'نشط' : 'موقوف')}</small></div>
                    <strong className="admin-stock-value">{product.quantity} {product.unit}</strong>
                  </div>
                  <p className="admin-stock-threshold">
                    {t('شراء')} {product.purchasePrice.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪ · {t('بيع')} {product.salePrice.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪
                  </p>
                  <p className={low ? 'admin-low-stock' : 'admin-stock-threshold'}>
                    {t(low ? 'المخزون منخفض' : 'حد التنبيه')} · {product.lowStockThreshold} {product.unit}
                  </p>
                  <div className="admin-inline-actions">
                    <button className="admin-secondary-button" type="button"
                      onClick={() => openMovementDialog(product, 'record')}><Plus size={15} /> {t('تسجيل حركة')}</button>
                    <button className="admin-secondary-button" type="button"
                      onClick={() => openMovementDialog(product, 'history')}><ScrollText size={15} /> {t('السجل')}</button>
                    <button className="admin-secondary-button" type="button" onClick={() => setEditing(product)}>{t('تعديل المنتج')}</button>
                    <button className="admin-secondary-button admin-delete-product-button" type="button"
                      disabled={busy} onClick={() => void deleteProduct(product)}><Trash2 size={15} /> {t('حذف المنتج')}</button>
                  </div>
                  {movementDialog?.product.id === product.id && createPortal(
                    <div className="admin-booking-modal-backdrop" role="presentation"
                      onMouseDown={(event) => {
                        if (event.target === event.currentTarget && !busy) setMovementDialog(null)
                      }}>
                      <section className="admin-booking-modal admin-inventory-movement-modal" role="dialog" aria-modal="true"
                        aria-labelledby="inventory-movement-title">
                        <div className="admin-booking-modal-heading">
                          <div><span className="admin-eyebrow">{t('المخزون والحركات')}</span>
                            <h2 id="inventory-movement-title">{product.name}</h2></div>
                          <button type="button" className="admin-icon-button" aria-label={t('إغلاق')}
                            onClick={() => setMovementDialog(null)} disabled={busy}><X size={17} /></button>
                        </div>
                        <div className="admin-inventory-modal-tabs">
                          <button type="button" className={movementDialog.view === 'record' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
                            onClick={() => setMovementDialog({ product, view: 'record' })}>{t('تسجيل حركة')}</button>
                          <button type="button" className={movementDialog.view === 'history' ? 'admin-filter-pill admin-filter-pill--active' : 'admin-filter-pill'}
                            onClick={() => openMovementDialog(product, 'history')}>{t('سجل الحركات')}</button>
                        </div>
                        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
                        {movementDialog.view === 'record' ? (
                      <form className="admin-payment-form" onSubmit={(event) => void recordMovement(event, product)}>
                        <label>{t('نوع الحركة')}<select name="movementType" value={movementTypes[product.id] ?? 'PURCHASE'}
                          onChange={(event) => setMovementTypes((types) => ({
                            ...types, [product.id]: event.target.value as AdminInventoryMovement['movementType'],
                          }))}>
                          {Object.entries(movementNames).map(([value, name]) =>
                            <option value={value} key={value}>{t(name)}</option>)}
                        </select></label>
                        <label>{t('الكمية')}<input name="quantity" type="number" min="0.001" step="0.001" required /></label>
                        <label>{(movementTypes[product.id] ?? 'PURCHASE') === 'PURCHASE'
                          ? t('تكلفة الشراء للوحدة') : t('تكلفة الوحدة (اختياري)')}
                          <input name="unitCost" type="number" min="0" step="0.01"
                            required={(movementTypes[product.id] ?? 'PURCHASE') === 'PURCHASE'} />
                        </label>
                        {(movementTypes[product.id] ?? 'PURCHASE') === 'SALE' && (
                          <label>{t('سعر البيع للوحدة (اختياري)')}
                            <input name="unitPrice" type="number" min="0" step="0.01" />
                          </label>
                        )}
                        <label>{t('ملاحظة')}<input name="notes" maxLength={1000} /></label>
                        <button className="admin-primary-button" disabled={busy}>{busy ? t('جارٍ الحفظ...') : t('حفظ الحركة')}</button>
                      </form>
                        ) : <>
                      <h3 className="admin-subheading">{t('آخر الحركات')}</h3>
                      {history[product.id]?.length ? history[product.id].map((movement) => (
                        <div className="admin-history-row" key={movement.id}>
                          <span>{t(movementNames[movement.movementType])}</span>
                          <strong>{movement.quantity.toLocaleString(language)} {product.unit}</strong>
                          {movement.movementType === 'SALE' && (movement.unitPrice !== null
                            ? <span>{t('سعر الوحدة')} {movement.unitPrice.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪ · {t('الإجمالي')} {(movement.lineTotal ?? movement.unitPrice * movement.quantity).toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪</span>
                            : <span>{t('سعر البيع غير مسجل لهذه الحركة')}</span>)}
                          {movement.movementType !== 'SALE' && movement.unitCost !== null
                            && <span>{t('تكلفة الوحدة')} {movement.unitCost.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪</span>}
                          <small>{new Date(movement.createdAt).toLocaleString(language)}</small>
                          {movement.notes && <small>{movement.notes}</small>}
                        </div>
                      )) : <p className="admin-state">{t('لا توجد حركات مسجلة.')}</p>}
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
            aria-labelledby="inventory-create-title">
            <div className="admin-booking-modal-heading">
              <div><span className="admin-eyebrow">{t('إضافة منتج')}</span><h2 id="inventory-create-title">{t('منتج جديد')}</h2></div>
              <button type="button" className="admin-icon-button" aria-label={t('إغلاق')}
                onClick={() => setCreateModalOpen(false)} disabled={busy}><X size={17} /></button>
            </div>
            <form className="admin-form" onSubmit={(event) => void createProduct(event)}>
              <label>{t('اسم المنتج')}<input name="name" maxLength={150} required /></label>
              <label>{t('وحدة القياس')}<input name="unit" maxLength={30} placeholder={t('قطعة، عبوة، مل...')} required /></label>
              <label>{t('الكمية المتوفرة')}<input name="availableQuantity" type="number" min="0" step="0.001" defaultValue="0" required /></label>
              <label>{t('سعر الشراء للوحدة (₪)')}<input name="purchasePrice" type="number" min="0" step="0.01" required /></label>
              <label>{t('سعر البيع للوحدة (₪)')}<input name="salePrice" type="number" min="0.01" step="0.01" required /></label>
              <label>{t('حد تنبيه المخزون المنخفض')}<input name="lowStockThreshold" type="number" min="0" step="1" defaultValue="1" required /></label>
              <ProductImagePicker />
              {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
              <button className="admin-primary-button" disabled={busy}>
                <TrendingUp size={17} /> {busy ? t('جارٍ الإضافة...') : t('إضافة المنتج')}
              </button>
            </form>
            <p className="admin-form-note"><TrendingDown size={15} /> {t('كل حركة تحفظ في سجل منفصل ولا يمكن أن تجعل الرصيد سالباً.')}</p>
          </section>
        </div>,
        document.body,
      )}
      {editing && createPortal(
        <div className="admin-booking-modal-backdrop" role="presentation"
          onMouseDown={(event) => { if (event.target === event.currentTarget) setEditing(null) }}>
          <section className="admin-booking-modal admin-inventory-edit-modal" role="dialog" aria-modal="true"
            aria-labelledby="inventory-edit-title">
            <div className="admin-booking-modal-heading">
              <div><span className="admin-eyebrow">{t('إدارة المخزون')}</span>
                <h2 id="inventory-edit-title">{t('تعديل المنتج')} {editing.name}</h2></div>
              <button type="button" className="admin-icon-button" aria-label={t('إغلاق')} onClick={() => setEditing(null)}>
                <X size={17} />
              </button>
            </div>
            {editing.imageUrl && <img className="admin-product-preview" src={editing.imageUrl} alt={editing.name} />}
            <form className="admin-form" onSubmit={(event) => void updateProduct(event)}>
              <label>{t('اسم المنتج')}<input name="name" defaultValue={editing.name} maxLength={150} required /></label>
              <label>{t('الوحدة')}<input name="unit" defaultValue={editing.unit} maxLength={30} required /></label>
              <label>{t('الكمية المتوفرة')}<input value={`${editing.quantity} ${editing.unit}`} readOnly /></label>
              <label>{t('سعر الشراء للوحدة (₪)')}<input name="purchasePrice" type="number" min="0" step="0.01"
                defaultValue={editing.purchasePrice} required /></label>
              <label>{t('سعر البيع للوحدة (₪)')}<input name="salePrice" type="number" min="0.01" step="0.01"
                defaultValue={editing.salePrice} required /></label>
              <label>{t('حد تنبيه المخزون')}<input name="lowStockThreshold" type="number" min="0" step="1"
                defaultValue={editing.lowStockThreshold} required /></label>
              <ProductImagePicker />
              <label className="admin-checkbox"><input type="checkbox" name="active" defaultChecked={editing.active} /> {t('المنتج نشط')}</label>
              <button className="admin-primary-button" disabled={busy}>{t('حفظ التعديل')}</button>
              <button className="admin-secondary-button" type="button" onClick={() => setEditing(null)}>{t('إلغاء')}</button>
            </form>
          </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminInventoryPage
