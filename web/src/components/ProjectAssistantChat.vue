<script lang="ts" setup>
import {nextTick, onMounted, reactive, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import {chatStream} from '@/api/assistant'
import type {SnowflakeId} from '@/types/api'
import type {AssistantReferenceVO, ChatMessage} from '@/types/assistant'
import {
  clearConversationId,
  clearMessages,
  loadConversationId,
  loadMessages,
  saveConversationId,
  saveMessages
} from '@/utils/assistantConversation'
import {type AnswerSegment, parseAnswerSegments} from '@/utils/answerCitation'
import StructuredFactsCard from '@/components/StructuredFactsCard.vue'
import ComparisonResult from '@/components/ComparisonResult.vue'

/**
 * 项目助手对话面板（Phase D 基础对话 + Phase E 富渲染）
 *
 * - 项目作用域会话：conversationId 按 aifp_assistant_conversation_${projectId} 隔离（硬性规则）
 * - 消息流：用户消息 → assistant 占位（loading）→ delta 增量追加（streaming，Phase K）→
 *   final 完整响应收尾 / 失败移除占位或保留已输出内容
 * - 会话上下文：conversationId 与消息列表均本地持久化（后端 Phase 9 提供历史接口前，保持
 *   Tab 切换 / 刷新后上下文不丢）
 * - Phase E 渲染（§8.3-§8.5）：answer 按 [S{n}]/[D{n}] 标记分段渲染上标角标（数据只取
 *   citations 回退 references，禁止自建来源对象）；最新一条 assistant 消息下挂冲突卡与
 *   比较卡；参考来源折叠列表中 FILE 条目可跳转文件 Tab
 */
const props = defineProps<{ projectId: SnowflakeId }>()

const router = useRouter()

// 本地消息列表（UI 层 ChatMessage）
const messages = ref<ChatMessage[]>([])
// 输入内容
const input = ref('')
// 请求发送中（占用位消息期间）
const sending = ref(false)
// 流式请求中断控制器（Phase K：组件卸载时中断进行中的流）
let abortController: AbortController | null = null
// 消息滚动容器
const scrollbarRef = ref()

// ===== 初始化与持久化 =====

/** 挂载/切换项目：恢复会话上下文 */
function restoreContext() {
  messages.value = loadMessages(String(props.projectId))
}

// 持久化当前消息（剥离占位由 saveMessages 内部处理）
function persist() {
  saveMessages(String(props.projectId), messages.value)
}

/** 滚动到底部（消息追加后） */
async function scrollToBottom() {
  await nextTick()
  scrollbarRef.value?.setScrollPercentage(1)
}

// ===== 发送 =====

/**
 * 发送消息（Phase K 流式版；Enter 触发，Shift+Enter 换行由 textarea 默认行为处理）
 *
 * 事件流：用户消息 → assistant 占位（loading）→ delta 增量追加正文（streaming）→
 * final 以完整响应收尾（answer 全量覆盖 + 引用/冲突/比较卡渲染）→ 失败兜底收敛。
 * 占位消息用 reactive() 包装：流式期间通过占位引用直接改属性，避免「普通对象入列后
 * 原始引用不触发响应式」陷阱。
 */
async function handleSend() {
  const text = input.value.trim()
  if (!text || sending.value) return

  const now = new Date().toISOString()
  // 用户消息 + assistant 占位（reactive 包装保证流式增量渲染）
  messages.value.push({role: 'user', content: text, timestamp: now})
  const placeholder = reactive<ChatMessage>({
    role: 'assistant',
    content: '',
    timestamp: now,
    loading: true,
    streaming: false
  })
  messages.value.push(placeholder)
  input.value = ''
  sending.value = true
  persist()
  scrollToBottom()

  abortController = new AbortController()
  try {
    await chatStream(
        props.projectId,
        {
          conversationId: loadConversationId(String(props.projectId)) || undefined,
          message: text
        },
        {
          // delta：切出流式态并逐块追加正文（final 前禁止点击来源跳转等富渲染）
          onDelta: (delta) => {
            placeholder.loading = false
            placeholder.streaming = true
            placeholder.content += delta
            scrollToBottom()
          },
          // final：完整响应覆盖（answer/引用/冲突/比较数据以 final 为准）
          onFinal: (res) => {
            placeholder.content = res.answer
            placeholder.response = res
            placeholder.loading = false
            placeholder.streaming = false
            // 会话 ID 按项目隔离存储
            saveConversationId(String(props.projectId), res.conversationId)
          }
        },
        abortController.signal
    )
  } catch (e) {
    // 中断（卸载/超时）静默；其余（error 事件/HTTP 错误/网络中断）统一提示
    const aborted = e instanceof DOMException && (e.name === 'AbortError' || e.name === 'TimeoutError')
    if (!aborted) {
      ElMessage.error(e instanceof Error && e.message ? e.message : '网络异常，请稍后重试')
    }
    // 已有流式输出则保留已输出内容并退出流式态；否则移除占位消息
    if (placeholder.content) {
      placeholder.streaming = false
    } else {
      const idx = messages.value.indexOf(placeholder)
      if (idx >= 0) {
        messages.value.splice(idx, 1)
      }
    }
  } finally {
    abortController = null
    sending.value = false
    persist()
    scrollToBottom()
  }
}

// ===== 新会话 =====

/** 新会话：清空消息列表并删除当前项目的会话 key（下轮由服务端发新 UUID） */
function handleNewConversation() {
  messages.value = []
  clearConversationId(String(props.projectId))
  clearMessages(String(props.projectId))
}

// 格式化时间
function formatTime(timestamp: string): string {
  return new Date(timestamp).toLocaleString('zh-CN')
}

// ===== Phase E：引用/卡片/来源渲染辅助（数据只取 response，禁止自建来源对象） =====

/** answer 分段（[S{n}]/[D{n}] 标记 → citationId 锚点） */
function segmentsOf(m: ChatMessage): AnswerSegment[] {
  return parseAnswerSegments(m.content)
}

/** 角标对应的来源成员：先 citations 按 citationId 匹配，未命中回退 references */
function citationOf(m: ChatMessage, citationId: string): AssistantReferenceVO | null {
  const pool = m.response?.citations?.length ? m.response.citations : m.response?.references ?? []
  return pool.find((r) => r.citationId === citationId) ?? null
}

/** 是否为最新一条 assistant 消息（冲突卡/比较卡仅挂最新一轮，§8.1） */
function isLatestAssistant(index: number): boolean {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') {
      return i === index
    }
  }
  return false
}

/** 参考来源列表：citations 为空时回退 references */
function sourceListOf(m: ChatMessage): AssistantReferenceVO[] {
  return m.response?.citations?.length ? m.response.citations : m.response?.references ?? []
}

/** FILE 来源条目的 fileId（点击跳转项目文件 Tab 用） */
function fileRefId(r: AssistantReferenceVO): string | null {
  const id = r.type === 'FILE' ? r.fileId : r.sourceFileId
  return id ? String(id) : null
}

/** FILE 来源条目点击：跳转本项目文件 Tab（activeTab 已与 route.query.tab 同步） */
function goFiles() {
  router.push({path: `/project/${props.projectId}`, query: {tab: 'files'}})
}

onMounted(restoreContext)

// 切换项目（防御性：组件在 Tab lazy 渲染下 projectId 理论上不变）
watch(() => props.projectId, restoreContext)
</script>

<template>
  <div class="assistant-chat">
    <!-- 头部：标题 + 新会话 -->
    <div class="chat-header">
      <span class="chat-title">
        <el-icon>
          <ChatDotRound/>
        </el-icon>
        智能助手
      </span>
      <el-button size="small" @click="handleNewConversation">
        <el-icon>
          <RefreshLeft/>
        </el-icon>
        新会话
      </el-button>
    </div>

    <!-- 消息区 -->
    <div class="chat-body">
      <el-empty
          v-if="messages.length === 0"
          description="向 AI 助手提问关于本项目的信息，例如：项目总投资是多少？"
      />
      <el-scrollbar v-else ref="scrollbarRef" class="chat-scroll">
        <div class="message-list">
          <div
              v-for="(m, i) in messages"
              :key="i"
              :class="['message-row', m.role === 'user' ? 'is-user' : 'is-assistant']"
          >
            <div class="bubble">
              <!-- assistant 占位（请求中） -->
              <span v-if="m.loading" class="loading-text">思考中...</span>
              <!-- answer 分段渲染：文本段 + 引用上标角标（数据只取 citations 回退 references） -->
              <template v-else-if="m.role === 'assistant'">
                <template v-for="(seg, si) in segmentsOf(m)" :key="si">
                  <span v-if="seg.type === 'text'" class="bubble-text">{{ seg.text }}</span>
                  <!-- 命中来源成员：可点击角标 -->
                  <el-popover
                      v-else-if="citationOf(m, seg.citationId)"
                      :width="260"
                      placement="top"
                      trigger="click"
                  >
                    <template #reference>
                      <sup class="citation-sup">[{{ seg.citationId }}]</sup>
                    </template>
                    <div class="citation-pop">
                      <template v-if="citationOf(m, seg.citationId)!.type === 'STRUCTURED'">
                        <div class="pop-value">
                          {{ citationOf(m, seg.citationId)!.rawValue }}{{ citationOf(m, seg.citationId)!.unit ?? '' }}
                        </div>
                        <div class="pop-source">
                          {{
                            citationOf(m, seg.citationId)!.sourceFileName || '未知来源'
                          }}{{
                            citationOf(m, seg.citationId)!.sourcePage ? ` 第${citationOf(m, seg.citationId)!.sourcePage}页` : ''
                          }}
                        </div>
                      </template>
                      <template v-else>
                        <div class="pop-value">{{ citationOf(m, seg.citationId)!.fileName }}</div>
                        <div class="pop-source">
                          {{
                            citationOf(m, seg.citationId)!.page ? `第${citationOf(m, seg.citationId)!.page}页` : '文档切片'
                          }}
                        </div>
                      </template>
                    </div>
                  </el-popover>
                  <!-- 未命中（理论上不出现）：渲染为纯文本，不报错 -->
                  <span v-else class="bubble-text">[{{ seg.citationId }}]</span>
                </template>
              </template>
              <!-- user 消息纯文本 -->
              <span v-else class="bubble-text">{{ m.content }}</span>
            </div>

            <!-- Phase E：参考来源折叠列表（citations 空则回退 references） -->
            <el-collapse v-if="m.role === 'assistant' && !m.loading && sourceListOf(m).length" class="source-collapse">
              <el-collapse-item :title="`参考来源（${sourceListOf(m).length} 条）`">
                <div
                    v-for="(r, ri) in sourceListOf(m)"
                    :key="ri"
                    :class="['source-item', { 'is-clickable': fileRefId(r) }]"
                    @click="fileRefId(r) && goFiles()"
                >
                  <span class="source-mark">[{{ r.citationId }}]</span>
                  <span class="source-text">
                    {{
                      r.type === 'FILE'
                          ? `${r.fileName}${r.page ? ` 第${r.page}页` : ''}`
                          : `${r.fieldName}：${r.rawValue}${r.unit ?? ''}（${r.sourceFileName || '未知来源'}${r.sourcePage ? ` 第${r.sourcePage}页` : ''}）`
                    }}
                  </span>
                  <el-icon v-if="fileRefId(r)" class="source-go">
                    <Position/>
                  </el-icon>
                </div>
              </el-collapse-item>
            </el-collapse>

            <!-- Phase E：字段事实卡（仅最新一条 assistant 消息，§8.1） -->
            <StructuredFactsCard
                v-if="isLatestAssistant(i) && m.response?.structuredData?.length"
                :facts="m.response.structuredData"
            />

            <!-- Phase E：跨项目比较卡（仅最新一条 assistant 消息，纯展示零计算） -->
            <ComparisonResult
                v-if="isLatestAssistant(i) && m.response?.comparisonData"
                :data="m.response.comparisonData"
            />
            <div class="message-time">{{ formatTime(m.timestamp) }}</div>
          </div>
        </div>
      </el-scrollbar>
    </div>

    <!-- 输入区 -->
    <div class="chat-input-bar">
      <el-input
          v-model="input"
          :autosize="{minRows: 1, maxRows: 4}"
          class="chat-input"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          type="textarea"
          @keydown.enter.exact.prevent="handleSend"
      />
      <el-button :disabled="!input.trim() || sending" :loading="sending" type="primary" @click="handleSend">
        发送
      </el-button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.assistant-chat {
  display: flex;
  flex-direction: column;
  height: 600px;

  .chat-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-bottom: 12px;
    border-bottom: 1px solid #e4e7ed;

    .chat-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 600;
    }
  }

  .chat-body {
    flex: 1;
    min-height: 0;
    padding: 12px 4px;

    .chat-scroll {
      height: 100%;
    }

    .message-list {
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding-right: 8px;
    }

    .message-row {
      display: flex;
      flex-direction: column;
      max-width: 78%;

      &.is-user {
        align-self: flex-end;
        align-items: flex-end;

        .bubble {
          background-color: #409eff;
          color: #fff;
        }
      }

      &.is-assistant {
        align-self: flex-start;
        align-items: flex-start;

        .bubble {
          background-color: #f5f7fa;
          color: #303133;
        }
      }

      .bubble {
        padding: 10px 14px;
        border-radius: 8px;
        font-size: 14px;
        line-height: 1.6;
        word-break: break-word;
      }

      .bubble-text {
        white-space: pre-wrap;
      }

      /* 引用上标角标（可点击） */
      .citation-sup {
        cursor: pointer;
        color: #409eff;
        font-weight: 600;
        margin: 0 1px;

        &:hover {
          text-decoration: underline;
        }
      }

      /* 参考来源折叠列表 */
      .source-collapse {
        margin-top: 6px;
        width: 100%;
        border: none;

        :deep(.el-collapse-item__header) {
          height: 32px;
          font-size: 12px;
          color: #909399;
          background-color: transparent;
          border-bottom: none;
        }

        :deep(.el-collapse-item__wrap) {
          background-color: transparent;
          border-bottom: none;
        }

        .source-item {
          display: flex;
          align-items: baseline;
          gap: 6px;
          padding: 3px 0;
          font-size: 12px;
          line-height: 1.6;

          &.is-clickable {
            cursor: pointer;

            &:hover .source-text {
              color: #409eff;
            }
          }

          .source-mark {
            color: #409eff;
            font-weight: 600;
            flex-shrink: 0;
          }

          .source-text {
            color: #606266;
          }

          .source-go {
            font-size: 12px;
            color: #c0c4cc;
          }
        }
      }

      .loading-text {
        color: #909399;
        font-style: italic;
      }

      /* Phase K：流式输出光标（闪烁） */
      .streaming-cursor {
        display: inline-block;
        width: 2px;
        height: 1em;
        margin-left: 2px;
        vertical-align: -0.15em;
        background-color: #409eff;
        animation: cursor-blink 0.8s step-start infinite;
      }

      .message-time {
        margin-top: 4px;
        font-size: 12px;
        color: #c0c4cc;
      }
    }
  }

  .chat-input-bar {
    display: flex;
    gap: 12px;
    align-items: flex-end;
    padding-top: 12px;
    border-top: 1px solid #e4e7ed;

    .chat-input {
      flex: 1;
    }
  }
}

/* 引用 popover 渲染在 body 下（teleport），需全局样式 */
.citation-pop {
  .pop-value {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    margin-bottom: 4px;
  }

  .pop-source {
    font-size: 12px;
    color: #909399;
  }
}

/* Phase K：流式输出光标闪烁动画 */
@keyframes cursor-blink {
  50% {
    opacity: 0;
  }
}
</style>
