import { describe, expect, it } from 'vitest'
import type { ThreadAssistantMessagePart } from '@assistant-ui/react'
import type { ChatBlock, ToolCallBlock } from '@/chat/types'
import { buildProcessSections, completedProcessSections, workingLabel, ProcessDisclosureStore } from './processParts'

const reasoning = (id: string): ThreadAssistantMessagePart => ({
    type: 'reasoning', text: 'Thinking', providerMetadata: { hapi: { blockId: id } },
})
const tool = (id: string, name = 'Bash', children: ChatBlock[] = [], permission?: ToolCallBlock['tool']['permission']): ThreadAssistantMessagePart => ({
    type: 'tool-call', toolCallId: id, toolName: name, argsText: '', args: {},
    artifact: { kind: 'tool-call', id, localId: null, createdAt: 1, children,
        tool: { id, name, input: {}, description: null, state: 'completed', createdAt: 1,
            startedAt: 1, completedAt: 2, execStartedAt: null, execCompletedAt: null, permission } } satisfies ToolCallBlock,
})

describe('process disclosure projection', () => {
    it('finishing folds interim prose and all steps into one row but keeps final answer and actions', () => {
        const parts: ThreadAssistantMessagePart[] = [{ type: 'text', text: 'Progress' }, reasoning('r'),
            tool('approve', 'Bash', [], { id: 'p', status: 'pending' }), tool('t'),
            { type: 'text', text: 'More progress' }, { type: 'text', text: 'Final answer' }]
        expect(completedProcessSections(parts)).toMatchObject([
            { kind: 'process', indices: [0, 1, 3, 4] }, { kind: 'part', index: 2 }, { kind: 'part', index: 5 },
        ])
        expect(buildProcessSections(parts).filter(s => s.kind === 'part')).toHaveLength(4)
    })
    it('does not discard multi-part final text or generated media', () => {
        const text = (text: string): ThreadAssistantMessagePart => ({ type: 'text', text,
            providerMetadata: { hapi: { localId: 'same-final' } } })
        expect(completedProcessSections([reasoning('r'), text('Section A'), text('Section B'),
            { type: 'image', image: 'image.png' }]).map(s => s.kind)).toEqual(['process', 'part', 'part', 'part'])
    })
    it('uses elapsed duration without inventing missing history timestamps', () => {
        expect(workingLabel(true, 131999)).toBe('Working for 2m 11s')
        expect(workingLabel(false, 131999)).toBe('Worked for 2m 11s')
        expect(workingLabel(false, undefined)).toBe('Worked')
        expect(workingLabel(false, -1)).toBe('Worked')
    })
    it('folds the screenshot sequence into one card while leaving prose visible', () => {
        const parts = [reasoning('r1'), tool('t1'), reasoning('r2'), tool('t2', 'Edit'), tool('t3'),
            { type: 'text', text: 'Final answer' } as const]
        const groups = buildProcessSections(parts)
        expect(groups).toHaveLength(2)
        expect(groups[0]).toMatchObject({ kind: 'process', thoughts: 2, operations: 3, indices: [0, 1, 2, 3, 4] })
        expect(groups[1]).toMatchObject({ kind: 'part', index: 5 })
        expect(parts).toHaveLength(6)
    })
    it('never folds plain replies, media, plans, approvals, questions or child-agent entries', () => {
        for (const name of ['update_plan', 'TodoWrite', 'ExitPlanMode', 'AskUserQuestion', 'request_user_input', 'CodexPermission', 'Task', 'CodexAgent']) {
            const sections = buildProcessSections([reasoning('a'), tool('t', name), reasoning('b')])
            expect(sections.map(s => s.kind), name).toEqual(['process', 'part', 'process'])
        }
        for (const boundary of [{ type: 'text', text: 'Prose' }, { type: 'image', image: 'image.png' },
            { type: 'tool-call', toolCallId: 'image', toolName: 'GeneratedImage', argsText: '', artifact: { kind: 'generated-image' } }] as ThreadAssistantMessagePart[]) {
            expect(buildProcessSections([reasoning('a'), boundary, reasoning('b')]).map(s => s.kind))
                .toEqual(['process', 'part', 'process'])
        }
    })
    it('does not hide nested approvals or generated images', () => {
        const approval = tool('p', 'Bash', [], { id: 'perm', status: 'pending' })
        const child = approval.type === 'tool-call' ? approval.artifact as ToolCallBlock : undefined
        expect(buildProcessSections([tool('parent', 'Read', [child!])])[0].kind).toBe('part')
        const image = { kind: 'generated-image', id: 'i', imageId: 'i', fileName: 'i.png', mimeType: 'image/png',
            localId: null, createdAt: 1 } as ChatBlock
        expect(buildProcessSections([tool('parent', 'Read', [image])])[0].kind).toBe('part')
    })
    it('folds assistant terminal output but keeps user shell output standalone', () => {
        const part = { type: 'text', text: 'Output', providerMetadata: { hapi: { kind: 'cli-output', blockId: 'cli', source: 'assistant' } } } as const
        expect(buildProcessSections([part])[0]).toMatchObject({ kind: 'process', operations: 1 })
        expect(buildProcessSections([{ ...part, providerMetadata: { hapi: { ...part.providerMetadata.hapi, source: 'user' } } }])[0].kind).toBe('part')
    })
    it('keeps unknown tool formats visible rather than hiding an unrecognized interaction', () => {
        expect(buildProcessSections([{ type: 'tool-call', toolCallId: 'unknown', toolName: 'Unknown', argsText: '', args: {} }])[0].kind).toBe('part')
    })
})

describe('session-local disclosure decisions', () => {
    it('preserves disclosure when standalone tools are absorbed into a tool group', () => {
        const first = tool('a')
        const second = tool('b')
        if (first.type !== 'tool-call' || second.type !== 'tool-call') throw new Error('Fixture')
        const group: ThreadAssistantMessagePart = { type: 'tool-call', toolCallId: 'tool-group:a',
            toolName: 'ToolGroup', argsText: '', args: {},
            artifact: { kind: 'tool-group', tools: [first.artifact, second.artifact] } }
        const raw = buildProcessSections([first])[0]
        const aggregated = buildProcessSections([group])[0]
        if (raw.kind !== 'process' || aggregated.kind !== 'process') throw new Error('Expected process')
        const store = new ProcessDisclosureStore()
        store.setOpen(raw.memberIds, true)
        expect(store.isOpen(aggregated.memberIds)).toBe(true)
        store.remember(aggregated.memberIds)
        expect(store.isOpen(['tool:b'])).toBe(true)
    })
    it('defaults closed and preserves open state across prepend, streaming, recycling and trim', () => {
        const store = new ProcessDisclosureStore()
        expect(store.isOpen(['r1', 't1'])).toBe(false)
        store.setOpen(['r1', 't1'], true)
        expect(store.isOpen(['older', 'r1', 't1', 'new'])).toBe(true)
        store.remember(['older', 'r1', 't1', 'new'])
        expect(store.isOpen(['new'])).toBe(true)
        store.setOpen(['new'], false)
        expect(store.isOpen(['older', 'r1', 't1', 'new'])).toBe(false)
        store.remember(['older', 'r1', 't1', 'new'])
        expect(store.isOpen(['older'])).toBe(false)
    })
    it('notifies subscribers and keeps sessions isolated', () => {
        const store = new ProcessDisclosureStore()
        let calls = 0
        const unsub = store.subscribe(() => calls++)
        store.setOpen(['a'], true)
        expect(calls).toBe(1)
        expect(new ProcessDisclosureStore().isOpen(['a'])).toBe(false)
        unsub(); store.setOpen(['a'], false)
        expect(calls).toBe(1)
    })
})
