import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AssistantRuntimeProvider, ThreadPrimitive, useExternalStoreRuntime, type ThreadMessageLike } from '@assistant-ui/react'
import '../src/index.css'
import { I18nProvider } from '../src/lib/i18n-context'
import { HappyChatProvider } from '../src/components/AssistantChat/context'
import { ProcessMessageContent, WorkingStatus } from '../src/components/AssistantChat/messages/ProcessMessageContent'

const thought = (id: string, text: string) => ({ type: 'reasoning' as const, text,
    providerMetadata: { hapi: { blockId: id } } })
const command = { type: 'tool-call' as const, toolCallId: 'command', toolName: 'Bash', args: {}, argsText: '',
    artifact: { kind: 'tool-call', id: 'command', localId: null, createdAt: 1, children: [],
        tool: { id: 'command', name: 'Bash', state: 'completed', input: { command: 'echo operation-detail' },
            description: null, createdAt: 1, startedAt: 1, completedAt: 2, execStartedAt: null, execCompletedAt: null } } }
const initial = [thought('r1', 'Reasoning detail A'), command, thought('r2', 'Reasoning detail B')]

const start = Date.now() - 131000
function Content({ parts, mounted, running }: { parts: typeof initial; mounted: boolean; running: boolean }) {
    const messages: ThreadMessageLike[] = [{
        id: 'response', role: 'assistant', content: [{ type: 'text', text: 'I am checking the implementation.' }, ...parts,
            { type: 'text', text: running ? 'Still working on verification.' : 'Final answer remains visible.' }],
        metadata: { custom: { processDurationMs: 131000 } },
    }]
    const runtime = useExternalStoreRuntime({ messages, convertMessage: message => message, isRunning: running, onNew: async () => {} })
    return <AssistantRuntimeProvider runtime={runtime}>
        <ThreadPrimitive.Root>{mounted ? <><ThreadPrimitive.Messages components={{ AssistantMessage: ProcessMessageContent }} />
            <WorkingStatus startedAt={start} /></> : null}</ThreadPrimitive.Root>
    </AssistantRuntimeProvider>
}

function App() {
    const [parts, setParts] = useState(initial)
    const [mounted, setMounted] = useState(true)
    const [sessionId, setSessionId] = useState('process-fixture')
    const [running, setRunning] = useState(false)
    return <I18nProvider><main style={{ maxWidth: 800, padding: 20, margin: 'auto' }}>
        <nav className="mb-4 flex flex-wrap gap-3">
            <button onClick={() => setRunning(true)}>Start working</button>
            <button onClick={() => setRunning(false)}>Finish</button>
            <button onClick={() => setParts(old => [...old, thought('stream', 'New streamed reasoning')])}>Stream</button>
            <button onClick={() => setParts(old => [thought('older', 'Older reasoning'), ...old])}>Prepend</button>
            <button onClick={() => setMounted(false)}>Recycle</button>
            <button onClick={() => setMounted(true)}>Restore</button>
            <button onClick={() => setSessionId('different-session')}>Other session</button>
        </nav>
        <HappyChatProvider value={{ api: {} as never, sessionId, metadata: null, terminalToolDisplayMode: 'detailed',
            showSessionSummaryInChat: true, disabled: true, onRefresh: () => {}, hasMoreMessages: false,
            isSyncingTail: false, isLoadingMoreMessages: false, loadOlderMessagesPreservingScroll: async () => 'terminal-stop' }}>
            <Content parts={parts} mounted={mounted} running={running} />
        </HappyChatProvider>
    </main></I18nProvider>
}
createRoot(document.getElementById('root')!).render(<App />)
