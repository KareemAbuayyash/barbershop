import { Clock3, Scissors } from 'lucide-react'
import { services } from '../../../config/site'

interface ServiceCardsProps {
  limit?: number
}

function ServiceCards({ limit }: ServiceCardsProps) {
  const visibleServices = limit ? services.slice(0, limit) : services

  return (
    <div className={`service-grid${limit ? ' service-grid--preview' : ''}`}>
      {visibleServices.map((service) => (
        <article className="service-card" key={service.name}>
          <div className="service-card-top">
            <span className="service-number">{service.number}</span>
            <span className="service-icon" aria-hidden="true"><Scissors size={19} strokeWidth={1.5} /></span>
          </div>
          <h3>{service.name}</h3>
          <p>{service.description}</p>
          <div className="service-meta">
            {service.duration && (
              <span className="service-duration"><Clock3 size={14} aria-hidden="true" />{service.duration}</span>
            )}
            <span className="service-price">السعر التجريبي <b>{service.samplePrice}</b></span>
          </div>
        </article>
      ))}
    </div>
  )
}

export default ServiceCards
