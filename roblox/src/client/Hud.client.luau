local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local CollectionService = game:GetService("CollectionService")

local player = Players.LocalPlayer

local gui = Instance.new("ScreenGui")
gui.Name = "Hud"
gui.ResetOnSpawn = false
gui.Parent = player:WaitForChild("PlayerGui")

local label = Instance.new("TextLabel")
label.Size = UDim2.fromOffset(220, 50)
label.Position = UDim2.new(0.5, -110, 0, 12)
label.BackgroundColor3 = Color3.fromRGB(20, 20, 30)
label.BackgroundTransparency = 0.3
label.TextColor3 = Color3.fromRGB(255, 210, 0)
label.Font = Enum.Font.GothamBold
label.TextSize = 24
label.Parent = gui
Instance.new("UICorner").Parent = label

local coins = player:WaitForChild("leaderstats"):WaitForChild("Coins")
local function update()
	label.Text = `Coins: {coins.Value}`
end
coins:GetPropertyChangedSignal("Value"):Connect(update)
update()

-- Spin every coin locally (cheap, and keeps the server light).
RunService.RenderStepped:Connect(function()
	local angle = os.clock() * 2
	for _, coin in CollectionService:GetTagged("Coin") do
		coin.CFrame = CFrame.new(coin.Position) * CFrame.Angles(0, angle, math.rad(90))
	end
end)
