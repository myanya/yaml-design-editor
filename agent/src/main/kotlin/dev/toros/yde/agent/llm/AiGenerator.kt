package dev.toros.yde.agent.llm

object AiGenerator {
    suspend fun generate(provider: AiProvider, input: AiGenerationInput): AiGenerated = when (provider) {
        AiProvider.OPENAI, AiProvider.AZURE -> OpenAiGenerator.generate(input, provider)
        AiProvider.ANTHROPIC -> AnthropicGenerator.generate(input)
    }
}
