import { useEffect } from 'react'
import { useI18n } from '../../lib/i18n'

let nextFieldId = 0

function validationMessage(field: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement): string {
  const validity = field.validity
  if (validity.valueMissing) {
    return field instanceof HTMLSelectElement ? 'اختر خيارًا من القائمة.' : 'هذا الحقل مطلوب.'
  }
  if (validity.typeMismatch) {
    if (field instanceof HTMLInputElement && field.type === 'email') return 'أدخل بريدًا إلكترونيًا صحيحًا.'
    if (field instanceof HTMLInputElement && field.type === 'url') return 'أدخل رابطًا صحيحًا.'
    return 'تحقق من صيغة البيانات المدخلة.'
  }
  if (validity.patternMismatch) return 'تحقق من صيغة البيانات المدخلة.'
  if (validity.tooShort && !(field instanceof HTMLSelectElement)) return `أدخل ${field.minLength} أحرف على الأقل.`
  if (validity.tooLong && !(field instanceof HTMLSelectElement)) return `اختصر النص إلى ${field.maxLength} حرفًا أو أقل.`
  if (validity.rangeUnderflow && field instanceof HTMLInputElement) return `يجب أن تكون القيمة ${field.min} أو أكثر.`
  if (validity.rangeOverflow && field instanceof HTMLInputElement) return `يجب أن تكون القيمة ${field.max} أو أقل.`
  if (validity.stepMismatch) return 'أدخل قيمة متوافقة مع الخيارات المتاحة.'
  if (validity.badInput) return 'أدخل قيمة صحيحة.'
  if (validity.customError) return field.validationMessage
  return 'تحقق من البيانات المدخلة.'
}

function isValidatableField(target: EventTarget | null): target is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement {
  return target instanceof HTMLInputElement
    || target instanceof HTMLSelectElement
    || target instanceof HTMLTextAreaElement
}

function clearFieldError(field: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement) {
  if (!field.validity.valid) return
  const errorId = field.dataset.customValidationError
  if (errorId) document.getElementById(errorId)?.remove()
  delete field.dataset.customValidationError
  field.removeAttribute('aria-invalid')
  const describedBy = field.getAttribute('aria-describedby')?.split(/\s+/).filter((id) => id !== errorId)
  if (describedBy?.length) field.setAttribute('aria-describedby', describedBy.join(' '))
  else field.removeAttribute('aria-describedby')
}

function showFieldError(field: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement, translate: (text: string) => string) {
  field.setAttribute('aria-invalid', 'true')
  let fieldId = field.id
  if (!fieldId) {
    fieldId = `custom-validation-field-${++nextFieldId}`
    field.id = fieldId
  }

  let message = field.dataset.customValidationError
    ? document.getElementById(field.dataset.customValidationError)
    : null
  if (!message) {
    const messageId = `${fieldId}-validation-error`
    message = document.createElement('span')
    message.id = messageId
    message.className = 'custom-validation-message'
    message.setAttribute('role', 'alert')
    field.dataset.customValidationError = messageId
    const anchor = field.closest('label') ?? field
    anchor.insertAdjacentElement('afterend', message)
    const describedBy = field.getAttribute('aria-describedby')?.split(/\s+/).filter(Boolean) ?? []
    if (!describedBy.includes(messageId)) field.setAttribute('aria-describedby', [...describedBy, messageId].join(' '))
  }
  message.textContent = translate(validationMessage(field))
}

function CustomFormValidation() {
  const { t } = useI18n()
  useEffect(() => {
    let focusFrame = 0
    const onInvalid = (event: Event) => {
      if (!isValidatableField(event.target)) return
      event.preventDefault()
      showFieldError(event.target, t)
      if (!focusFrame) {
        const firstInvalid = event.target
        focusFrame = window.requestAnimationFrame(() => {
          firstInvalid.focus()
          focusFrame = 0
        })
      }
    }
    const onEdit = (event: Event) => {
      if (isValidatableField(event.target)) clearFieldError(event.target)
    }

    document.addEventListener('invalid', onInvalid, true)
    document.addEventListener('input', onEdit, true)
    document.addEventListener('change', onEdit, true)
    return () => {
      document.removeEventListener('invalid', onInvalid, true)
      document.removeEventListener('input', onEdit, true)
      document.removeEventListener('change', onEdit, true)
      if (focusFrame) window.cancelAnimationFrame(focusFrame)
    }
  }, [t])

  return null
}

export default CustomFormValidation
