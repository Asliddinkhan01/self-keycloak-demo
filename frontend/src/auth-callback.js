import { forwardAuthorizationResponse } from './login-callback.js'

// Entry point of auth-callback.html. The logic lives in login-callback.js, where it can be tested.
forwardAuthorizationResponse(window)
