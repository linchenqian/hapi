import { useEffect, useId, useMemo, useState, useSyncExternalStore } from 'react'
import { MessagePrimitive, MessagePartPrimitive, useAuiState, type TextMessagePartProps } from '@assistant-ui/react'
import { Reasoning } from '@/components/assistant-ui/reasoning'
import { HappyToolMessage } from '@/components/AssistantChat/messages/ToolMessage'
import { NotifySummaryText } from '@/components/AssistantChat/messages/NotifySummaryText'
import { CliOutputBlock } from '@/components/CliOutputBlock'
import { useHappyChatContext } from '@/components/AssistantChat/context'
import { useTranslation } from '@/lib/use-translation'
import { buildProcessSections, completedProcessSections, workingLabel, isCliProcessPart, processPartKey, type ProcessSection } from './processParts'
import type { HappyChatMessageMetadata } from '@/lib/assistant-runtime'

const CliText = ({ text }: TextMessagePartProps) => <CliOutputBlock text={text} />
const InlineImage = () => <MessagePartPrimitive.Image />
const COMPONENTS = { Text: NotifySummaryText, Reasoning, Image: InlineImage, tools: { Fallback: HappyToolMessage } } as const
const CLI_COMPONENTS = { ...COMPONENTS, Text: CliText }

function ProcessCard(props: { section: Extract<ProcessSection, { kind: 'process' }>; label?: string; children: React.ReactNode }) {
    const { t } = useTranslation()
    const ctx = useHappyChatContext()
    const store = ctx.processDisclosure!
    const contentId = useId()
    const ids = props.section.memberIds
    const open = useSyncExternalStore(store.subscribe, () => store.isOpen(ids), () => false)
    useEffect(() => { store.remember(ids) }, [store, ids, open])
    return (
        <section className="my-2 min-w-0 max-w-full overflow-hidden text-sm text-[var(--app-hint)]"
            data-hapi-process-card="true" data-hapi-share-exclude="true">
            <button type="button" aria-expanded={open} aria-controls={contentId}
                data-testid="process-card-toggle"
                className="flex max-w-full items-center gap-2 py-1 text-left text-[13px]"
                onClick={() => {
                    ctx.onNestedScrollFollowChange?.(false)
                    store.setOpen(ids, !open)
                }}>
                <span className="min-w-0">{props.label ?? t('processCard.summary', { operations: props.section.operations, thoughts: props.section.thoughts })}</span>
                <span aria-hidden="true">{open ? '⌄' : '›'}</span>
                {props.section.errors > 0 ? <span className="text-xs text-red-500">
                    {t('processCard.errors', { n: props.section.errors })}
                </span> : null}
                {props.section.running ? <span aria-label={t('processCard.running')}
                    className="h-1 w-1 shrink-0 animate-pulse rounded-full bg-current" /> : null}
            </button>
            {open ? <div id={contentId} data-testid="process-card-content"
                className="max-h-[min(36rem,65vh)] overflow-y-auto overscroll-contain pl-3 py-1 text-[13px]"
                onWheel={() => ctx.onNestedScrollFollowChange?.(false)}
                onTouchStart={() => ctx.onNestedScrollFollowChange?.(false)}>
                {props.children}
            </div> : null}
        </section>
    )
}

/** Fold at the final renderer so summary/copy/timing/paging never lose source blocks. */
export function ProcessMessageContent() {
    const parts = useAuiState(state => state.message.parts)
    const running = useAuiState(state => state.message.status?.type === 'running')
    const meta = useAuiState(state => state.message.metadata.custom as Partial<HappyChatMessageMetadata>)
    const sections = useMemo(() => running ? buildProcessSections(parts) : completedProcessSections(parts), [parts, running])
    const duration = meta?.roundSummary?.durationMs ?? meta?.processDurationMs ?? meta?.durationMs
    const renderPart = (index: number) => <MessagePrimitive.PartByIndex
        key={processPartKey(parts[index], index)} index={index}
        components={isCliProcessPart(parts[index]) ? CLI_COMPONENTS : COMPONENTS} />
    return sections.map(section => section.kind === 'process'
        ? <ProcessCard key={section.id} section={section} label={running ? undefined : workingLabel(false, duration)}>{section.indices.map(renderPart)}</ProcessCard>
        : <MessagePrimitive.PartByIndex key={section.id} index={section.index} components={COMPONENTS} />)
}

/** The server's turn start (or the invoking user message) survives remount and SSE reconnect. */
export function WorkingStatus({ startedAt }: { startedAt?: number | null }) {
    const running = useAuiState(state => state.thread.isRunning)
    const fallbackStart = useAuiState(state => {
        const user = state.thread.messages.findLast(message => message.role === 'user'
            && !(message.metadata.custom as Partial<HappyChatMessageMetadata>)?.steered)
        return (user?.metadata.custom as Partial<HappyChatMessageMetadata>)?.invokedAt ?? user?.createdAt?.getTime()
    })
    const [now, setNow] = useState(Date.now)
    useEffect(() => {
        if (!running) return
        setNow(Date.now())
        const timer = setInterval(() => setNow(Date.now()), 1000)
        return () => clearInterval(timer)
    }, [running])
    const start = startedAt ?? fallbackStart
    return running ? <div data-testid="working-status" className="py-2 px-1 text-[13px] text-[var(--app-hint)]">
        {workingLabel(true, start != null ? Math.max(0, now - start) : null)}
    </div> : null
}
