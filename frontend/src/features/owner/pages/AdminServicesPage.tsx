import { useEffect, useState, type FormEvent } from 'react'
import { Archive, Plus, Scissors } from 'lucide-react'
import { apiRequest, getErrorMessage, type Service } from '../../../lib/api'

function AdminServicesPage() {
  const [services, setServices] = useState<Service[]>([])
  const [editing, setEditing] = useState<Service | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  const activeServices = services.filter((service) => service.active)
  const visibleServices = showArchived ? services : activeServices

  const load = async () => {
    try {
      setServices(await apiRequest<Service[]>('/api/services/manage'))
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر تحميل الخدمات.'))
    }
  }
  useEffect(() => {
    let active = true
    void apiRequest<Service[]>('/api/services/manage')
      .then((data) => { if (active) setServices(data) })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل الخدمات.'))
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
      if (editing) {
        await apiRequest(`/api/services/${editing.id}`, { method: 'PUT', body: JSON.stringify(request) })
        setNotice('تم تحديث الخدمة.')
      } else {
        await apiRequest('/api/services', { method: 'POST', body: JSON.stringify(request) })
        setNotice('تمت إضافة الخدمة.')
      }
      setEditing(null)
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر حفظ الخدمة.'))
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
      setError(getErrorMessage(cause, 'تعذر تحديث حالة الخدمة.'))
    } finally {
      setBusy(false)
    }
  }

  const archiveAllActive = async () => {
    if (!activeServices.length || !window.confirm(
      `سيتم إيقاف ${activeServices.length} خدمة عن الظهور للحجوزات الجديدة. لن تُحذف الحجوزات أو السجلات السابقة، ويمكن إعادة تفعيل الخدمات من الأرشيف. هل تريد المتابعة؟`,
    )) return

    setBusy(true)
    setError('')
    setNotice('')
    try {
      const archivedCount = await apiRequest<number>('/api/services/archive', { method: 'PATCH' })
      setEditing(null)
      setShowArchived(false)
      setNotice(`تمت أرشفة ${archivedCount.toLocaleString('ar')} خدمة مع الحفاظ على الحجوزات السابقة.`)
      await load()
    } catch (cause) {
      setError(getErrorMessage(cause, 'تعذر أرشفة الخدمات.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="admin-page admin-two-column">
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">قائمة الأسعار</span><h2>الخدمات</h2></div>
          <div className="admin-inline-actions">
            <span className="admin-result-count">{visibleServices.length.toLocaleString('ar')} خدمة</span>
            <button className="admin-secondary-button" type="button" disabled={busy}
              onClick={() => setShowArchived((value) => !value)}>
              {showArchived ? 'الخدمات النشطة' : 'عرض الأرشيف'}
            </button>
            {activeServices.length > 0 && <button className="admin-secondary-button" type="button"
              disabled={busy} onClick={() => void archiveAllActive()}>
              <Archive size={14} /> أرشفة النشطة
            </button>}
          </div>
        </div>
        {error && <p className="admin-message admin-message--error" role="alert">{error}</p>}
        {notice && <p className="admin-message" role="status">{notice}</p>}
        {visibleServices.length === 0 ? <div className="admin-empty"><Scissors size={24} />
          <h3>{showArchived ? 'لا توجد خدمات في الأرشيف' : 'لا توجد خدمات نشطة'}</h3>
          {!showArchived && <p>أضف خدماتك الجديدة، أو افتح الأرشيف لإعادة تفعيل خدمة سابقة.</p>}
        </div> : (
          <div className="admin-service-list">
            {visibleServices.map((service) => (
              <article className="admin-service-row" key={service.id}>
                <div><strong>{service.name}</strong><p>{service.description}</p>
                  <small>{service.price.toLocaleString('ar')} ₪ · {service.durationMinutes} دقيقة · {service.active ? 'نشطة' : 'متوقفة'}</small></div>
                <div className="admin-inline-actions">
                  <button className="admin-secondary-button" type="button" disabled={busy} onClick={() => setEditing(service)}>تعديل</button>
                  <button className="admin-secondary-button" type="button" disabled={busy}
                    onClick={() => void toggle(service)}>{service.active ? 'إيقاف' : 'تفعيل'}</button>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
      <section className="admin-panel">
        <div className="admin-panel-heading"><div><span className="admin-eyebrow">{editing ? 'تحديث البيانات' : 'خدمة جديدة'}</span>
          <h2>{editing ? 'تعديل الخدمة' : 'إضافة خدمة'}</h2></div>
          <Plus size={20} /></div>
        <form className="admin-form" key={editing?.id ?? 'new'} onSubmit={(event) => void save(event)}>
          <label>اسم الخدمة<input name="name" minLength={2} maxLength={150} defaultValue={editing?.name} required /></label>
          <label>الوصف (اختياري)<textarea name="description" rows={3} maxLength={10000} defaultValue={editing?.description} /></label>
          <label>السعر (₪)<input name="price" type="number" min="0" step="0.01" defaultValue={editing?.price} required /></label>
          <label>المدة بالدقائق<input name="durationMinutes" type="number" min="1" step="1" defaultValue={editing?.durationMinutes} required /></label>
          <button className="admin-primary-button" disabled={busy}>{busy ? 'جارٍ الحفظ...' : editing ? 'حفظ التعديل' : 'إضافة الخدمة'}</button>
          {editing && <button className="admin-secondary-button" type="button" onClick={() => setEditing(null)}>إلغاء التعديل</button>}
        </form>
      </section>
    </div>
  )
}

export default AdminServicesPage
