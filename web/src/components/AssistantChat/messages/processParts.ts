import type { ThreadAssistantMessagePart, ThreadUserMessagePart } from '@assistant-ui/react'
import type { ChatBlock, ToolCallBlock } from '@/chat/types'
import { isEligibleForToolGrouping, type ToolGroupBlock } from '@/chat/toolGroups'
import { isObject } from '@hapi/protocol'
type DisplayPart = ThreadAssistantMessagePart | ThreadUserMessagePart

export type ProcessSection = {
    kind: 'process'
    id: string
    indices: number[]
    memberIds: string[]
    operations: number
    thoughts: number
    errors: number
    running: boolean
} | { kind: 'part'; id: string; index: number }

export function processPartKey(part: DisplayPart, index: number): string {
    if (part.type === 'tool-call') return 'tool:' + part.toolCallId
    if ('providerMetadata' in part) {
        const id = part.providerMetadata?.hapi?.blockId
        if (typeof id === 'string') return part.type + ':' + id
    }
    return part.type + ':' + index
}

export function isCliProcessPart(part: DisplayPart): boolean {
    return part.type === 'text' && part.providerMetadata?.hapi?.kind === 'cli-output'
        && part.providerMetadata.hapi.source !== 'user'
}

function toolCanFold(block: ToolCallBlock): boolean {
    // Never bury questions, proposals, permission decisions or child-agent entry points.
    const eligible = isEligibleForToolGrouping(block, 'grouped')
        || (block.tool.name === 'CodexReasoning' && block.tool.permission?.status !== 'pending')
    return eligible && block.children.every(childCanFold)
}

function childCanFold(block: ChatBlock): boolean {
    return block.kind === 'agent-reasoning'
        || (block.kind === 'cli-output' && block.source !== 'user')
        || (block.kind === 'tool-call' && toolCanFold(block))
}

function processSummary(part: DisplayPart): {
    operations: number; thoughts: number; errors: number; running: boolean
} | null {
    const running = 'status' in part && part.status?.type === 'running'
    if (part.type === 'reasoning') return { operations: 0, thoughts: 1, errors: 0, running }
    if (isCliProcessPart(part)) return { operations: 1, thoughts: 0, errors: 0, running }
    if (part.type !== 'tool-call' || !isObject(part.artifact)) return null
    const artifact = part.artifact
    const tools = artifact.kind === 'tool-group' && Array.isArray(artifact.tools)
        ? (artifact as ToolGroupBlock).tools
        : artifact.kind === 'tool-call' && isObject(artifact.tool) && Array.isArray(artifact.children)
            ? [artifact as ToolCallBlock] : null
    if (!tools || tools.length === 0 || !tools.every(toolCanFold)) return null
    return {
        operations: tools.filter(tool => tool.tool.name !== 'CodexReasoning').length,
        thoughts: tools.filter(tool => tool.tool.name === 'CodexReasoning').length,
        errors: tools.filter(tool => tool.tool.state === 'error').length,
        running: tools.some(tool => tool.tool.state === 'running' || tool.tool.state === 'pending'),
    }
}

/** Rendering only: original parts, copy text, runtime ids, history and sync stay untouched. */
export function buildProcessSections(parts: readonly DisplayPart[]): ProcessSection[] {
    const sections: ProcessSection[] = []
    parts.forEach((part, index) => {
        const id = processPartKey(part, index)
        const summary = processSummary(part)
        if (!summary) {
            sections.push({ kind: 'part', id, index })
            return
        }
        let group = sections.at(-1)
        if (group?.kind !== 'process') {
            group = { kind: 'process', id: 'process:' + id, indices: [], memberIds: [],
                operations: 0, thoughts: 0, errors: 0, running: false }
            sections.push(group)
        }
        group.indices.push(index)
        group.memberIds.push(id)
        // A lone tool may become an aggregate when its next sibling arrives.
        // Keep source identities so that change cannot reset disclosure state.
        if (part.type === 'tool-call' && isObject(part.artifact)
            && part.artifact.kind === 'tool-group' && Array.isArray(part.artifact.tools)) {
            for (const tool of part.artifact.tools as ToolCallBlock[]) group.memberIds.push('tool:' + tool.id)
        }
        group.operations += summary.operations
        group.thoughts += summary.thoughts
        group.errors += summary.errors
        group.running ||= summary.running
    })
    return sections
}

/** Only a finished response can fold progress prose. Keep the final contiguous
 * text run, media and actionable tools outside the disclosure. */
export function completedProcessSections(parts: readonly DisplayPart[]): ProcessSection[] {
    const lastText = parts.findLastIndex(part => part.type === 'text' && !isCliProcessPart(part))
    let finalStart = lastText
    const final = parts[lastText]
    const localId = final && 'providerMetadata' in final ? final.providerMetadata?.hapi?.localId : null
    while (localId && finalStart > 0) {
        const previous = parts[finalStart - 1]
        if (previous.type !== 'text' || previous.providerMetadata?.hapi?.localId !== localId || isCliProcessPart(previous)) break
        finalStart--
    }
    const indices = parts.flatMap((part, index) => processSummary(part)
        || (part.type === 'text' && !isCliProcessPart(part) && index < finalStart) ? [index] : [])
    if (indices.length === 0) return buildProcessSections(parts)
    const process: Extract<ProcessSection, { kind: 'process' }> = {
        kind: 'process', id: 'worked:' + processPartKey(parts[indices[0]], indices[0]),
        indices, memberIds: [], operations: 0, thoughts: 0, errors: 0, running: false,
    }
    for (const index of indices) {
        process.memberIds.push('worked:' + processPartKey(parts[index], index))
        const summary = processSummary(parts[index])
        if (summary) {
            process.operations += summary.operations; process.thoughts += summary.thoughts; process.errors += summary.errors
        }
    }
    const folded = new Set(indices)
    return parts.flatMap((part, index): ProcessSection[] => index === indices[0] ? [process]
        : folded.has(index) ? [] : [{ kind: 'part', id: processPartKey(part, index), index }])
}

export function workingLabel(working: boolean, durationMs?: number | null): string {
    const prefix = working ? 'Working' : 'Worked'
    if (durationMs == null || !Number.isFinite(durationMs) || durationMs < 0) return prefix
    const seconds = Math.floor(durationMs / 1000)
    return `${prefix} for ${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

type Decision = { open: boolean; revision: number }

/** Session-local disclosure state survives virtualization, regrouping and prepends.
 * Latest explicit decision wins when old members reappear; never persist chat text. */
export class ProcessDisclosureStore {
    private decisions = new Map<string, Decision>()
    private revision = 0
    private listeners = new Set<() => void>()
    subscribe = (listener: () => void) => {
        this.listeners.add(listener)
        return () => { this.listeners.delete(listener) }
    }
    private decision(ids: readonly string[]): Decision {
        let latest: Decision = { open: false, revision: 0 }
        for (const id of ids) {
            const value = this.decisions.get(id)
            if (value && value.revision > latest.revision) latest = value
        }
        return latest
    }
    isOpen = (ids: readonly string[]): boolean => this.decision(ids).open
    remember(ids: readonly string[]): void {
        const value = this.decision(ids)
        if (value.revision === 0) return
        for (const id of ids) this.decisions.set(id, value)
        while (this.decisions.size > 10_000) this.decisions.delete(this.decisions.keys().next().value!)
    }
    setOpen(ids: readonly string[], open: boolean): void {
        const value = { open, revision: ++this.revision }
        for (const id of ids) this.decisions.set(id, value)
        for (const listener of this.listeners) listener()
    }
}
