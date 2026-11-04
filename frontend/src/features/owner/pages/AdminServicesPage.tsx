import { useEffect, useRef, useState, type FormEvent, type PointerEvent } from 'react'
import { createPortal } from 'react-dom'
import { Archive, Plus, Scissors, X } from 'lucide-react'
import { apiRequest, getErrorMessage, type Service } from '../../../lib/api'
import { useI18n } from '../../../lib/i18n'

interface ServiceImageCrop {
  centerX: number
  centerY: number
  size: number
}

async function prepareServiceImage(image: File, crop: ServiceImageCrop): Promise<File> {
  const maxBytes = 1.8 * 1024 * 1024
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(image)
  } catch {
    throw new Error('تعذر فتح الصورة لضغطها. جرّب صورة بصيغة JPG أو PNG.')
  }

  try {
    const cropSize = Math.min(bitmap.width, bitmap.height) * crop.size
    const sourceX = Math.max(0, Math.min(bitmap.width - cropSize, crop.centerX * bitmap.width - cropSize / 2))
    const sourceY = Math.max(0, Math.min(bitmap.height - cropSize, crop.centerY * bitmap.height - cropSize / 2))
    const canvas = document.createElement('canvas')
    canvas.width = 1200
    canvas.height = 1200
    const context = canvas.getContext('2d')
    if (!context) throw new Error('تعذر تجهيز الصورة. جرّب مرة أخرى.')
    context.fillStyle = '#171613'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(bitmap, sourceX, sourceY, cropSize, cropSize, 0, 0, canvas.width, canvas.height)

    for (let attempt = 0; attempt < 6; attempt += 1) {
      const blob = await new Promise<Blob>((resolve, reject) => {
        canvas.toBlob((result) => result ? resolve(result) : reject(new Error('تعذر تجهيز الصورة.')),
          'image/jpeg', Math.max(0.5, 0.86 - attempt * 0.07))
      })
      if (blob.size <= maxBytes) {
        const name = image.name.replace(/\.[^.]+$/, '') || 'service-image'
        return new File([blob], `${name}.jpg`, { type: 'image/jpeg', lastModified: Date.now() })
      }
    }
    throw new Error('تعذر تصغير الصورة للحجم المطلوب. جرّب صورة أصغر.')
  } finally {
    bitmap.close()
  }
}

function AdminServicesPage() {
  const { t, language } = useI18n()
  const [services, setServices] = useState<Service[]>([])
  const [editing, setEditing] = useState<Service | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const [imagePreview, setImagePreview] = useState<string | null>(null)
  const [imageFile, setImageFile] = useState<File | null>(null)
  const [imageDimensions, setImageDimensions] = useState<{ width: number; height: number } | null>(null)
  const [imageCrop, setImageCrop] = useState<ServiceImageCrop>({ centerX: 0.5, centerY: 0.5, size: 1 })
  const imageEditorRef = useRef<HTMLDivElement>(null)
  const cropDragRef = useRef<{
    mode: 'move' | 'resize'
    pointerX: number
    pointerY: number
    left: number
    top: number
    side: number
    imageWidth: number
    imageHeight: number
    signX: number
    signY: number
  } | null>(null)
  const activeServices = services.filter((service) => service.active)
  const visibleServices = showArchived ? services : activeServices
  const cropStageRect = imageEditorRef.current?.getBoundingClientRect()
  const cropImageScale = imageDimensions && cropStageRect
    ? Math.min(cropStageRect.width / imageDimensions.width, cropStageRect.height / imageDimensions.height)
    : 0
  const cropImageWidth = imageDimensions ? imageDimensions.width * cropImageScale : 0
  const cropImageHeight = imageDimensions ? imageDimensions.height * cropImageScale : 0
  const cropBoxSide = Math.min(cropImageWidth, cropImageHeight) * imageCrop.size
  const cropBoxLeft = (cropStageRect ? (cropStageRect.width - cropImageWidth) / 2 : 0)
    + imageCrop.centerX * cropImageWidth - cropBoxSide / 2
  const cropBoxTop = (cropStageRect ? (cropStageRect.height - cropImageHeight) / 2 : 0)
    + imageCrop.centerY * cropImageHeight - cropBoxSide / 2

  const startCropPointer = (event: PointerEvent<HTMLDivElement>) => {
    if (!imageFile || !imageDimensions || !imageEditorRef.current) return
    const image = imageEditorRef.current.querySelector('img')
    if (!image) return
    const imageRect = image.getBoundingClientRect()
    const selection = event.target instanceof HTMLElement ? event.target.closest('[data-crop-selection]') : null
    if (!selection) return
    const corner = event.target instanceof HTMLElement ? event.target.closest<HTMLElement>('[data-crop-corner]')?.dataset.cropCorner : undefined
    const side = Math.min(imageRect.width, imageRect.height) * imageCrop.size
    const imageX = imageCrop.centerX * imageRect.width - side / 2
    const imageY = imageCrop.centerY * imageRect.height - side / 2
    const signX = corner?.includes('l') ? -1 : 1
    const signY = corner?.includes('t') ? -1 : 1
    cropDragRef.current = {
      mode: corner ? 'resize' : 'move',
      pointerX: event.clientX,
      pointerY: event.clientY,
      left: imageX,
      top: imageY,
      side,
      imageWidth: imageRect.width,
      imageHeight: imageRect.height,
      signX,
      signY,
    }
    imageEditorRef.current.setPointerCapture(event.pointerId)
    event.preventDefault()
  }

  const moveCropPointer = (event: PointerEvent<HTMLDivElement>) => {
    const drag = cropDragRef.current
    if (!drag) return
    const deltaX = event.clientX - drag.pointerX
    const deltaY = event.clientY - drag.pointerY
    let left = drag.left
    let top = drag.top
    let side = drag.side

    if (drag.mode === 'move') {
      left = Math.max(0, Math.min(drag.imageWidth - side, left + deltaX))
      top = Math.max(0, Math.min(drag.imageHeight - side, top + deltaY))
    } else {
      const anchorX = drag.signX < 0 ? drag.left + drag.side : drag.left
      const anchorY = drag.signY < 0 ? drag.top + drag.side : drag.top
      const maxSide = Math.min(
        drag.signX < 0 ? anchorX : drag.imageWidth - anchorX,
        drag.signY < 0 ? anchorY : drag.imageHeight - anchorY,
      )
      side = Math.max(Math.min(56, maxSide), Math.min(maxSide,
        drag.side + (drag.signX * deltaX + drag.signY * deltaY) / 2))
      left = drag.signX < 0 ? anchorX - side : anchorX
      top = drag.signY < 0 ? anchorY - side : anchorY
    }

    setImageCrop({
      centerX: (left + side / 2) / drag.imageWidth,
      centerY: (top + side / 2) / drag.imageHeight,
      size: side / Math.min(drag.imageWidth, drag.imageHeight),
    })
  }

  const load = async () => {
    try {
      setServices(await apiRequest<Service[]>('/api/services/manage'))
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تحميل الخدمات.')))
    }
  }
  useEffect(() => {
    let active = true
    void apiRequest<Service[]>('/api/services/manage')
      .then((data) => { if (active) setServices(data) })
      .catch((cause: unknown) => {
        if (active) setError(t(getErrorMessage(cause, 'تعذر تحميل الخدمات.')))
      })
    return () => { active = false }
  }, [])

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    const request = {
      name: String(formData.get('name')).trim(),
      description: String(formData.get('description')).trim(),
      price: Number(formData.get('price')),
      durationMinutes: Number(formData.get('durationMinutes')),
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      let savedService: Service
      if (editing) {
        savedService = await apiRequest<Service>(`/api/services/${editing.id}`, { method: 'PUT', body: JSON.stringify(request) })
      } else {
        savedService = await apiRequest<Service>('/api/services', { method: 'POST', body: JSON.stringify(request) })
      }
      const image = formData.get('image')
      if (image instanceof File && image.size > 0) {
        const upload = new FormData()
        upload.append('image', await prepareServiceImage(image, imageCrop))
        await apiRequest<void>(`/api/services/${savedService.id}/image`, { method: 'PUT', body: upload })
      }
        setNotice(t(editing ? 'تم تحديث الخدمة.' : 'تمت إضافة الخدمة.'))
      setEditing(null)
      setModalOpen(false)
      setImagePreview(null)
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر حفظ الخدمة.')))
    } finally {
      setBusy(false)
    }
  }

  const toggle = async (service: Service) => {
    setBusy(true)
    setError('')
    try {
      await apiRequest(`/api/services/${service.id}/active`, {
        method: 'PATCH',
        body: JSON.stringify({ active: !service.active }),
      })
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر تحديث حالة الخدمة.')))
    } finally {
      setBusy(false)
    }
  }

  const archiveAllActive = async () => {
    if (!activeServices.length || !window.confirm(t('سيتم إيقاف الخدمات النشطة عن الحجوزات الجديدة. يمكن إعادة تفعيلها من الأرشيف. هل تريد المتابعة؟'))) return

    setBusy(true)
    setError('')
    setNotice('')
    try {
      const archivedCount = await apiRequest<number>('/api/services/archive', { method: 'PATCH' })
      setEditing(null)
      setShowArchived(false)
      setNotice(t('تمت أرشفة الخدمات النشطة مع الحفاظ على الحجوزات السابقة.') + ` (${archivedCount.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')})`)
      await load()
    } catch (cause) {
      setError(t(getErrorMessage(cause, 'تعذر أرشفة الخدمات.')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="admin-page admin-services-page">
      <div className="admin-services-toolbar">
        <button className="admin-secondary-button" type="button" onClick={() => {
          setEditing(null)
          setImagePreview(null)
          setImageFile(null)
          setImageDimensions(null)
          setError('')
          setModalOpen(true)
        }}><Plus size={16} /> {t('إضافة خدمة')}</button>
      </div>
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">{t('قائمة الأسعار')}</span><h2>{t('الخدمات')}</h2></div>
          <div className="admin-inline-actions">
            <span className="admin-result-count">{visibleServices.length.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} {t('خدمة')}</span>
            <button className="admin-secondary-button" type="button" disabled={busy}
              onClick={() => setShowArchived((value) => !value)}>
              {showArchived ? t('الخدمات النشطة') : t('عرض الأرشيف')}
            </button>
            {activeServices.length > 0 && <button className="admin-secondary-button" type="button"
              disabled={busy} onClick={() => void archiveAllActive()}>
              <Archive size={14} /> {t('أرشفة النشطة')}
            </button>}
          </div>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {visibleServices.length === 0 ? <div className="admin-empty"><Scissors size={24} />
          <h3>{showArchived ? t('لا توجد خدمات في الأرشيف') : t('لا توجد خدمات نشطة')}</h3>
          {!showArchived && <p>{t('أضف خدماتك الجديدة، أو افتح الأرشيف لإعادة تفعيل خدمة سابقة.')}</p>}
        </div> : (
          <div className="admin-service-list">
            {visibleServices.map((service) => (
              <article className="admin-service-row" key={service.id}>
                <div><strong>{service.name}</strong><p>{service.description}</p>
                  <small>{service.price > 0 ? `${service.price.toLocaleString(language === 'ar' ? 'ar-PS' : 'en-US')} ₪` : t('السعر يحدد في المحل')} · {service.durationMinutes} {t('دقيقة')} · {t(service.active ? 'نشطة' : 'متوقفة')}</small></div>
                <div className="admin-inline-actions">
                  <button className="admin-secondary-button" type="button" disabled={busy} onClick={() => {
                    setEditing(service)
                    setImagePreview(service.imageUrl)
                    setImageFile(null)
                    setImageDimensions(null)
                    setError('')
                    setModalOpen(true)
                  }}>{t('تعديل')}</button>
                  <button className="admin-secondary-button" type="button" disabled={busy}
                    onClick={() => void toggle(service)}>{t(service.active ? 'إيقاف' : 'تفعيل')}</button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
      {modalOpen && createPortal(
        <div className="admin-booking-modal-backdrop" role="presentation"
          onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) setModalOpen(false) }}>
          <section className="admin-booking-modal admin-service-modal" role="dialog" aria-modal="true"
            aria-labelledby="service-modal-title">
            <div className="admin-booking-modal-heading">
              <div><span className="admin-eyebrow">{t(editing ? 'تحديث البيانات' : 'خدمة جديدة')}</span>
                <h2 id="service-modal-title">{t(editing ? 'تعديل الخدمة' : 'إضافة خدمة')}</h2></div>
              <button type="button" className="admin-icon-button" aria-label={t('إغلاق')}
                onClick={() => { setModalOpen(false); setEditing(null) }} disabled={busy}><X size={17} /></button>
            </div>
            {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
            <form className="admin-form" key={editing?.id ?? 'new'} onSubmit={(event) => void save(event)}>
              <div className="service-image-field">
                <label htmlFor="service-image-input">{t('صورة الخدمة (اختيارية)')}</label>
                <input id="service-image-input" name="image" type="file" accept="image/*" onChange={(event) => {
                  const file = event.currentTarget.files?.[0]
                  setImageFile(file ?? null)
                  setImagePreview(file ? URL.createObjectURL(file) : editing?.imageUrl ?? null)
                  setImageDimensions(null)
                  setImageCrop({ centerX: 0.5, centerY: 0.5, size: 1 })
                }} />
                {imagePreview ? <div className="service-image-crop-stage" ref={imageEditorRef}
                  onPointerDown={startCropPointer} onPointerMove={moveCropPointer}
                  onPointerUp={() => { cropDragRef.current = null }}
                  onPointerCancel={() => { cropDragRef.current = null }}>
                  <img src={imagePreview} alt={imageFile ? 'حدد الجزء المربع الظاهر من الصورة' : 'صورة الخدمة الحالية'}
                    onLoad={(event) => {
                      const { naturalWidth, naturalHeight } = event.currentTarget
                      if (naturalWidth && naturalHeight) setImageDimensions({ width: naturalWidth, height: naturalHeight })
                    }} />
                  {imageFile && imageDimensions && <div className="service-image-crop-selection" data-crop-selection
                    style={{ left: `${cropBoxLeft}px`, top: `${cropBoxTop}px`, width: `${cropBoxSide}px`, height: `${cropBoxSide}px` }}>
                    {(['tl', 'tr', 'bl', 'br'] as const).map((corner) => <span key={corner}
                      className={`service-image-crop-handle service-image-crop-handle--${corner}`} data-crop-corner={corner} />)}
                  </div>}
                </div> : <span className="service-image-current-placeholder"><Scissors size={26} aria-hidden="true" /></span>}
                {imageFile && <small>{t('اسحب المربع لتحريكه، واسحب إحدى زواياه لتغيير حجمه.')}</small>}
                {!imageFile && <small>{t('اختر صورة من الهاتف أو الكمبيوتر لتحديد الجزء المربع الذي سيظهر.')}</small>}
              </div>
              <label>{t('اسم الخدمة')}<input name="name" minLength={2} maxLength={150} defaultValue={editing?.name} required /></label>
              <label>{t('الوصف (اختياري)')}<textarea name="description" rows={3} maxLength={10000} defaultValue={editing?.description} /></label>
              <label>{t('السعر (₪) — اختياري')}<input name="price" type="number" min="0" step="0.01" placeholder={t('يحدد في المحل')} defaultValue={editing?.price || ''} />
                <small>{t('اتركه فارغًا إذا كان السعر يحدد عند حضور الزبون.')}</small></label>
              <label>{t('المدة بالدقائق')}<input name="durationMinutes" type="number" min="1" step="1" defaultValue={editing?.durationMinutes} required /></label>
              <button className="admin-primary-button" disabled={busy}>
                {busy ? t('جارٍ الحفظ...') : editing ? t('حفظ التعديل') : t('إضافة الخدمة')}
              </button>
            </form>
          </section>
        </div>,
        document.body,
      )}
    </div>
  )
}

export default AdminServicesPage
