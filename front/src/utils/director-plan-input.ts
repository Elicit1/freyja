import type { DirectorPlanInput, DramaShot } from '@/types/drama'
import { ref, watch, onScopeDispose } from 'vue'
import { shotApi } from '@/api/drama'

const text = (value?: string | null) => value?.trim() || null

export function directorPlanInput(shot: Partial<DramaShot> | DirectorPlanInput): DirectorPlanInput {
  const sizeLocked = Boolean(shot.shotTypeLocked && text(shot.shotType) && shot.shotType?.trim().toUpperCase() !== 'AUTO')
  const movementLocked = Boolean(shot.cameraMovementLocked && text(shot.cameraMovement) && shot.cameraMovement?.trim().toUpperCase() !== 'AUTO')
  return {
    shotNo: shot.shotNo ?? 1, shotName: text(shot.shotName), duration: shot.duration ?? null,
    shotTypeLocked: sizeLocked, shotType: sizeLocked ? text(shot.shotType) : null,
    cameraMovementLocked: movementLocked, cameraMovement: movementLocked ? text(shot.cameraMovement) : null,
    scriptContent: text(shot.scriptContent), actionDescription: text(shot.actionDescription),
    soundEffect: text(shot.soundEffect)
  }
}

export function useDirectorInputCheck(source: () => Partial<DramaShot> | null) {
  const inputsChanged = ref(false)
  let sequence = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  watch(() => {
    const shot = source()
    return shot ? JSON.stringify([shot.id, shot.directorPlanInputHash, shot.directorPlanRevision, shot.directorPlanMode, directorPlanInput(shot)]) : ''
  }, () => {
    const requestSequence = ++sequence
    clearTimeout(timer)
    const shot = source()
    if (!shot?.id) { inputsChanged.value = false; return }
    const data = { shotId: shot.id, directorPlanInput: directorPlanInput(shot) }
    timer = setTimeout(async () => {
      try {
        const check = await shotApi.checkDirectorPlanInputs(data)
        if (requestSequence === sequence) inputsChanged.value = check.inputsChanged
      } catch {
        // Clicking local generation performs a fresh authoritative check and reports any error.
      }
    }, 300)
  }, { immediate: true })
  onScopeDispose(() => { ++sequence; clearTimeout(timer) })
  return { inputsChanged }
}
