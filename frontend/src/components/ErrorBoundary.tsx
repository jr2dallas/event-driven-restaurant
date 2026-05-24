import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props { children: ReactNode }
interface State { hasError: boolean; message: string }

export default class ErrorBoundary extends Component<Props, State> {
    state: State = { hasError: false, message: '' }

    static getDerivedStateFromError(error: Error): State {
        return { hasError: true, message: error.message }
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        console.error('[ErrorBoundary]', error, info.componentStack)
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="flex flex-col items-center justify-center bg-white/5 rounded-xl text-white/60 gap-3 text-sm">
                    <span className="text-2xl">⚠️</span>
                    <p>Le canvas n'a pas pu se charger.</p>
                    <p className="text-xs opacity-50">{this.state.message}</p>
                    <button
                        onClick={() => this.setState({ hasError: false, message: '' })}
                        className="px-4 py-2 bg-white/10 rounded-lg hover:bg-white/20 transition-colors cursor-pointer"
                    >
                        Réessayer
                    </button>
                </div>
            )
        }
        return this.props.children
    }
}
