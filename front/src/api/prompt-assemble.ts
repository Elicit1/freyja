import { request } from './request'
import type { PromptAssembleRequest, PromptAssembleResult } from '@/types/resource'

export const promptAssembleApi = {
  assemble(data: PromptAssembleRequest) {
    return request<PromptAssembleResult>({
      url: '/res/prompt/assemble',
      method: 'post',
      data
    })
  }
}
