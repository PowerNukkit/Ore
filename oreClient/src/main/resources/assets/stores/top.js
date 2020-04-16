const state = {
    alerts: {
        error: [],
        success: [],
        info: [],
        warning: []
    }
}

const mutations = {
    addAlert(state, payload) {
        state.alerts[payload.level].push({
            message: payload.message
        })
    },
    dismissAlert(state, payload) {
        state.alerts[payload.level].splice(payload.index, 1)
    },
    dismissAllAlert(state, payload) {
        state.alerts[payload.level] = []
    }
}

export default {
    state,
    mutations
}