import { CalendarDays, ListOrdered, Scissors, Sun } from 'lucide-react'
import type { BookingMode } from '../../../config/site'
import { bookingModes } from '../../../config/site'

const icons: Record<BookingMode['icon'], typeof CalendarDays> = {
  calendar: CalendarDays,
  barber: Scissors,
  queue: ListOrdered,
  today: Sun,
}

interface BookingModeCardsProps {
  selectedMode?: string
  onSelect?: (title: string) => void
}

function BookingModeCards({ selectedMode, onSelect }: BookingModeCardsProps) {
  return (
    <div className="booking-grid">
      {bookingModes.map((mode, index) => {
        const Icon = icons[mode.icon]
        const isSelected = selectedMode === mode.title
        const className = `booking-card${onSelect ? ' booking-card--selectable' : ''}${isSelected ? ' booking-card--selected' : ''}`
        const content = (
          <>
            <span className="booking-card-top">
              <span className="booking-index">0{index + 1}</span>
              <span className="booking-icon" aria-hidden="true"><Icon size={20} strokeWidth={1.5} /></span>
            </span>
            <span className="booking-card-title">{mode.title}</span>
            <span className="booking-card-description">{mode.description}</span>
            <span className="booking-card-rule" aria-hidden="true" />
          </>
        )

        return (
          onSelect ? (
            <button
              className={className}
              key={mode.title}
              type="button"
              aria-label={`${mode.title}: ${mode.description}`}
              aria-pressed={isSelected}
              onClick={() => onSelect(mode.title)}
            >
              {content}
            </button>
          ) : (
            <article className={className} key={mode.title}>
              <span className="booking-card-top">
                <span className="booking-index">0{index + 1}</span>
                <span className="booking-icon" aria-hidden="true"><Icon size={20} strokeWidth={1.5} /></span>
              </span>
              <h3>{mode.title}</h3>
              <p>{mode.description}</p>
              <span className="booking-card-rule" aria-hidden="true" />
            </article>
          )
        )
      })}
    </div>
  )
}

export default BookingModeCards
