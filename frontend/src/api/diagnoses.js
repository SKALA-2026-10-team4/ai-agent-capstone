import { get } from './client'

export const listDiagnoses = () => get('/service/api/diagnoses')
export const getDiagnosis = (threadId) => get(`/service/api/diagnoses/${encodeURIComponent(threadId)}`)
export const getChangeDelivery = (changeId) => get(`/api/skill-changes/${encodeURIComponent(changeId)}`)
