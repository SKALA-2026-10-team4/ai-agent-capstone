// SkillOps service 로그인. 응답: { access_token, token_type, expires_in, user: { username, display_name, roles } }
import { get, post } from './client'

export const login = (username, password) => post('/service/api/auth/login', { username, password })

export const me = () => get('/service/api/auth/me')
