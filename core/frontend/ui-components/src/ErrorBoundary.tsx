import { Component, type ErrorInfo, type ReactNode } from 'react';

type Props = { readonly children: ReactNode };
type State = { readonly failed: boolean };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false };
  static getDerivedStateFromError(): State { return { failed: true }; }
  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('DevQA React render failed', error, info.componentStack);
  }
  render(): ReactNode {
    return this.state.failed
      ? <div role="alert">DevQA frontend could not initialize. Reopen the dashboard.</div>
      : this.props.children;
  }
}
