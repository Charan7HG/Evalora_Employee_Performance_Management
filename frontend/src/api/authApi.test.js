import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError } from 'axios'
import apiClient, { getApiErrorMessage } from './axiosInstance.js'
import { changePassword, devResetPassword, getCurrentUser, login, logout } from './authApi.js'
import { TOKEN_STORAGE_KEY } from './tokenStorage.js'

const initialAdapter = apiClient.defaults.adapter

function setAdapter(handler) {
  apiClient.defaults.adapter = async (config) => {
    const result = await handler(config)
    if (result instanceof Error) {
      throw result
    }
    return {
      data: result.data,
      status: result.status ?? 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
}

function responseError(config, status, data = {}) {
  return new AxiosError(
    'Request failed',
    'ERR_BAD_REQUEST',
    config,
    null,
    { status, data, headers: {}, config },
  )
}

describe('authentication API client', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    apiClient.defaults.adapter = initialAdapter
  })

  it('posts the backend login DTO without adding a client-selected role', async () => {
    const credentials = {
      email: 'person@example.test',
      password: 'Example!234',
      clientRole: 'HR',
    }
    const payload = {
      token: 'signed.jwt.value',
      tokenType: 'Bearer',
      expiresIn: 28800,
      user: { id: 8, email: credentials.email, role: 'EMPLOYEE' },
    }
    let request
    setAdapter((config) => {
      request = config
      return { data: payload }
    })

    await expect(login(credentials)).resolves.toEqual(payload)

    expect(request.method).toBe('post')
    expect(request.url).toBe('/auth/login')
    expect(JSON.parse(request.data)).toEqual({
      email: 'person@example.test',
      password: 'Example!234',
    })
    expect(request.headers.get('Authorization')).toBeUndefined()
    expect(request.baseURL).toMatch(/\/api$/)
  })

  it('retrieves the current user using the stored bearer token', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'stored.jwt')
    let request
    setAdapter((config) => {
      request = config
      return { data: { id: 8, email: 'person@example.test', role: 'EMPLOYEE' } }
    })

    await getCurrentUser()

    expect(request.method).toBe('get')
    expect(request.url).toBe('/auth/me')
    expect(request.headers.get('Authorization')).toBe('Bearer stored.jwt')
  })

  it('uses the exact logout and change-password methods and DTO fields', async () => {
    const requests = []
    setAdapter((config) => {
      requests.push(config)
      return { data: { message: 'ok' } }
    })
    localStorage.setItem(TOKEN_STORAGE_KEY, 'stored.jwt')

    await logout()
    await changePassword({
      currentPassword: 'Old!23456',
      newPassword: 'New!23456',
      confirmPassword: 'New!23456',
      userId: 999,
    })

    expect(requests.map(({ method, url }) => [method, url])).toEqual([
      ['post', '/auth/logout'],
      ['put', '/auth/change-password'],
    ])
    expect(requests[1].headers.get('Authorization')).toBe('Bearer stored.jwt')
    expect(JSON.parse(requests[1].data)).toEqual({
      currentPassword: 'Old!23456',
      newPassword: 'New!23456',
      confirmPassword: 'New!23456',
    })
  })

  it('posts the direct development reset DTO without email or token', async () => {
    let request
    setAdapter((config) => {
      request = config
      return { data: { message: 'Password changed successfully.' } }
    })

    await devResetPassword({
      newPassword: 'New!23456',
      confirmPassword: 'New!23456',
      email: 'ignored@example.test',
      token: 'ignored',
    })

    expect(request.method).toBe('post')
    expect(request.url).toBe('/auth/dev-reset-password')
    expect(JSON.parse(request.data)).toEqual({
      newPassword: 'New!23456',
      confirmPassword: 'New!23456',
    })
    expect(request.headers.get('Authorization')).toBeUndefined()
  })

  it('clears stored auth on protected 401 responses and does not clear it for 403', async () => {
    const eventHandler = vi.fn()
    window.addEventListener('evalora:authentication-failure', eventHandler)
    localStorage.setItem(TOKEN_STORAGE_KEY, 'expired.jwt')
    setAdapter((config) => responseError(config, 401, { message: 'Unauthorized' }))

    await expect(getCurrentUser()).rejects.toBeInstanceOf(AxiosError)

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(eventHandler).toHaveBeenCalledOnce()

    localStorage.setItem(TOKEN_STORAGE_KEY, 'another.expired.jwt')
    setAdapter((config) => responseError(config, 401, { message: 'Unauthorized' }))
    await expect(getCurrentUser()).rejects.toBeInstanceOf(AxiosError)
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(eventHandler).toHaveBeenCalledTimes(2)

    localStorage.setItem(TOKEN_STORAGE_KEY, 'valid.jwt')
    setAdapter((config) => responseError(config, 403, { message: 'Forbidden' }))
    await expect(getCurrentUser()).rejects.toBeInstanceOf(AxiosError)

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('valid.jwt')
    expect(eventHandler).toHaveBeenCalledTimes(2)
    window.removeEventListener('evalora:authentication-failure', eventHandler)
  })

  it('maps common HTTP and backend-unavailable errors to safe messages', () => {
    expect(getApiErrorMessage({ response: { status: 400, data: { message: 'Email is required.' } } }))
      .toBe('Email is required.')
    expect(getApiErrorMessage({
      config: { url: '/auth/login' },
      response: { status: 401, data: { message: 'Invalid email or password.' } },
    }))
      .toBe('Invalid email or password.')
    expect(getApiErrorMessage({ response: { status: 403 } }))
      .toBe('You do not have permission to perform this action.')
    expect(getApiErrorMessage({ response: { status: 404, data: { message: 'Employee not found.' } } }))
      .toBe('Employee not found.')
    expect(getApiErrorMessage({ response: { status: 409, data: { message: 'Manager has active employees.' } } }))
      .toBe('Manager has active employees.')
    expect(getApiErrorMessage({ response: { status: 423, data: { message: 'Account locked.' } } }))
      .toBe('Account locked.')
    expect(getApiErrorMessage({ response: { status: 500, data: { message: 'internal stack detail' } } }))
      .toBe('Evalora could not complete your request. Please try again later.')
    expect(getApiErrorMessage(new axios.AxiosError('Network Error', 'ERR_NETWORK')))
      .toBe('Unable to reach Evalora. Check your connection and whether the backend is running.')
  })
})
