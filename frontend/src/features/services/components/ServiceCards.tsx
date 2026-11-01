import { useEffect, useState } from 'react'
import { Clock3, Scissors } from 'lucide-react'
import { apiRequest, getErrorMessage, type Service } from '../../../lib/api'

interface ServiceCardsProps {
  limit?: number
}

function ServiceCards({ limit }: ServiceCardsProps) {
  const [services, setServices] = useState<Service[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    void apiRequest<Service[]>('/api/services')
      .then((result) => {
        if (active) setServices(result)
      })
      .catch((cause: unknown) => {
        if (active) setError(getErrorMessage(cause, 'تعذر تحميل الخدمات حالياً.'))
      })
      .finally(() => {
        if (active) setIsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const visibleServices = limit ? services.slice(0, limit) : services
  const priceFormatter = new Intl.NumberFormat('ar-PS', {
    style: 'currency',
    currency: 'ILS',
    maximumFractionDigits: 2,
  })

  if (isLoading) {
    return <p className="api-state" role="status">جارٍ تحميل الخدمات...</p>
  }
  if (error) {
    return <p className="api-state api-state--error" role="alert">{error}</p>
  }
  if (!visibleServices.length) {
    return <p className="api-state">لا توجد خدمات متاحة للحجز حالياً.</p>
  }

  return (
    <div className={`service-grid${limit ? ' service-grid--preview' : ''}`}>
      {visibleServices.map((service, index) => (
        <article className="service-card" key={service.id}>
          <div className="service-card-top">
            <span className="service-number">{String(index + 1).padStart(2, '0')}</span>
            <span className="service-icon" aria-hidden="true"><Scissors size={19} strokeWidth={1.5} /></span>
          </div>
          <h3>{service.name}</h3>
          <p>{service.description}</p>
          <div className="service-meta">
            <span className="service-duration"><Clock3 size={14} aria-hidden="true" />{service.durationMinutes} دقيقة</span>
            <span className="service-price"><b>{priceFormatter.format(service.price)}</b></span>
          </div>
        </article>
      ))}
    </div>
  )
}

export default ServiceCards
