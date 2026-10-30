import { MessagePrimitive, useAuiState } from '@assistant-ui/react'
import { ProcessMessageContent } from './ProcessMessageContent'
import type { HappyChatMessageMetadata } from '@/lib/assistant-runtime'
import { getAssistantCopyText } from '@/components/AssistantChat/messages/assistantCopyText'
import { getConversationMessageAnchorId } from '@/chat/outline'
import { CodexReviewCard } from '@/components/AssistantChat/messages/CodexReviewCard'
import { MessageActions } from '@/components/AssistantChat/messages/MessageActions'
import { useHappyChatContext } from '@/components/AssistantChat/context'
import { useSessionSummaryInChat } from '@/hooks/useSessionSummaryInChat'

export function HappyAssistantMessage() {
    const ctx = useHappyChatContext()
    const showSessionSummaryInChat = useSessionSummaryInChat()
    const messageId = useAuiState((s) => s.message.id)
    const elementId = getConversationMessageAnchorId(messageId)
    const codexReview = useAuiState((s) => {
        const custom = s.message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined
        return custom?.kind === 'codex-review' ? custom.review : undefined
    })
    const toolOnly = useAuiState((s) => {
        if (s.message.role !== 'assistant') return false
        const parts = s.message.content
        return parts.length > 0 && parts.every((part) => part.type === 'tool-call')
    })
    const copyText = useAuiState((s) => {
        if (s.message.role !== 'assistant') return ''
        return getAssistantCopyText(s.message.content, {
            stripNotifySummary: !showSessionSummaryInChat
        })
    })

    const durationMs = useAuiState(({ message }) => (message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined)?.durationMs)
    const usage = useAuiState(({ message }) => (message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined)?.usage)
    const messageModel = useAuiState(({ message }) => (message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined)?.model)
    const turnCount = useAuiState(({ message }) => (message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined)?.turnCount)
    const roundSummary = useAuiState(({ message }) => (message.metadata.custom as Partial<HappyChatMessageMetadata> | undefined)?.roundSummary)

    const metadata = { durationMs, usage, model: messageModel ?? null, turnCount, roundSummary }

    const history = ctx.metadata?.capabilities?.conversationHistory
    const showForkCurrent = Boolean(
        history?.forkCurrent
        && ctx.isLatestCompletedBoundary?.(messageId)
        && !ctx.disabled
        && ctx.onForkConversation
    )

    const rootClass = toolOnly
        ? 'py-1 min-w-0 max-w-full overflow-x-hidden'
        : 'px-1 min-w-0 max-w-full overflow-x-hidden'

    return (
        <MessagePrimitive.Root
            id={elementId}
            data-hapi-message-role="assistant"
            className={`happy-message ${rootClass} scroll-mt-4`}
        >
            {codexReview
                    ? <CodexReviewCard review={codexReview} />
                    : <ProcessMessageContent />}
            <MessageActions
                align="start"
                copyText={copyText || undefined}
                metadata={metadata}
                messageElementId={elementId}
                showFork={showForkCurrent}
                historyActionPending={ctx.historyActionPending}
                onFork={showForkCurrent ? () => ctx.onForkConversation!() : undefined}
            />
        </MessagePrimitive.Root>
    )
}
