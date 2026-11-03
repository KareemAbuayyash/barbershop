import { CalendarDays, ListOrdered, Scissors } from 'lucide-react'
import type { BookingMode as BookingModeOption } from '../../../config/site'
import type { BookingMode as BookingModeValue } from '../../../lib/api'
import { bookingModes } from '../../../config/site'

const icons: Record<BookingModeOption['icon'], typeof CalendarDays> = {
  calendar: CalendarDays,
  barber: Scissors,
  queue: ListOrdered,
}

interface BookingModeCardsProps {
  enabledModes: BookingModeValue[]
  selectedMode?: BookingModeValue
  onSelect: (mode: BookingModeValue) => void
}

function BookingModeCards({ enabledModes, selectedMode, onSelect }: BookingModeCardsProps) {
  const availableModes = bookingModes.filter((mode: BookingModeOption) => enabledModes.includes(mode.value))

  return (
    <div className="booking-grid">
      {availableModes.map((mode, index) => {
        const Icon = icons[mode.icon]
        const isSelected = selectedMode === mode.value
        const className = `booking-card booking-card--selectable${isSelected ? ' booking-card--selected' : ''}`
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
          <button
            className={className}
            key={mode.value}
            type="button"
            aria-label={`${mode.title}: ${mode.description}`}
            aria-pressed={isSelected}
            onClick={() => onSelect(mode.value)}
          >
            {content}
          </button>
        )
      })}
    </div>
  )
}

export default BookingModeCards
