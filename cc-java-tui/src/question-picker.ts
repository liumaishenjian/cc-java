export interface QuestionOptionView {
  readonly optionId: string;
  readonly label: string;
  readonly description: string;
}

export interface QuestionPickerState {
  readonly callId: string;
  readonly selectedIndex: number;
  readonly submitted: boolean;
}

/** 为 Java 权威问题创建纯本地光标状态。 */
export function createQuestionPicker(callId: string): QuestionPickerState {
  return {callId, selectedIndex: 0, submitted: false};
}

/** 在服务端给出的封闭选项内循环移动。 */
export function moveQuestionPicker(
  state: QuestionPickerState,
  optionCount: number,
  delta: -1 | 1,
): QuestionPickerState {
  return {...state, selectedIndex: (state.selectedIndex + delta + optionCount) % optionCount};
}
