local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local UserInputService = game:GetService("UserInputService")
local TweenService = game:GetService("TweenService")

local player = Players.LocalPlayer
local Remotes = ReplicatedStorage:WaitForChild("Remotes")
local Config = require(ReplicatedStorage:WaitForChild("Shared"):WaitForChild("Config"))

local function new(class, props, parent)
	local inst = Instance.new(class)
	for k, v in props do
		inst[k] = v
	end
	inst.Parent = parent
	return inst
end

local function label(props, parent)
	props.BackgroundTransparency = props.BackgroundTransparency or 1
	props.Font = props.Font or Enum.Font.GothamBold
	props.TextColor3 = props.TextColor3 or Color3.new(1, 1, 1)
	props.TextScaled = props.TextScaled == nil and false or props.TextScaled
	return new("TextLabel", props, parent)
end

local gui = new("ScreenGui", { Name = "Hud", ResetOnSpawn = false, IgnoreGuiInset = true }, player:WaitForChild("PlayerGui"))

-- Crosshair + hit marker ------------------------------------------------
local cross = new("Frame", { Size = UDim2.fromOffset(40, 40), Position = UDim2.fromScale(0.5, 0.5), AnchorPoint = Vector2.new(0.5, 0.5), BackgroundTransparency = 1 }, gui)
for _, r in { { 0, -14, 2, 8 }, { 0, 6, 2, 8 }, { -14, 0, 8, 2 }, { 6, 0, 8, 2 } } do
	new("Frame", {
		Position = UDim2.new(0.5, r[1] - (r[3] == 2 and 1 or 0), 0.5, r[2] - (r[4] == 2 and 1 or 0)),
		Size = UDim2.fromOffset(r[3], r[4]), BackgroundColor3 = Color3.new(1, 1, 1), BorderSizePixel = 0,
	}, cross)
end
local marker = new("Frame", { Size = UDim2.fromOffset(30, 30), Position = UDim2.fromScale(0.5, 0.5), AnchorPoint = Vector2.new(0.5, 0.5), BackgroundTransparency = 1, Visible = false }, gui)
local markerBars = {}
for _, rot in { 45, -45 } do
	table.insert(markerBars, new("Frame", {
		Size = UDim2.fromOffset(30, 3), Position = UDim2.fromScale(0.5, 0.5), AnchorPoint = Vector2.new(0.5, 0.5),
		Rotation = rot, BorderSizePixel = 0, BackgroundColor3 = Color3.new(1, 1, 1),
	}, marker))
end
local markerToken = 0
Remotes.Hit.OnClientEvent:Connect(function(headshot, killed)
	markerToken += 1
	local token = markerToken
	local color = killed and Color3.fromRGB(255, 80, 80) or headshot and Color3.fromRGB(255, 210, 60) or Color3.new(1, 1, 1)
	for _, bar in markerBars do
		bar.BackgroundColor3 = color
	end
	marker.Visible = true
	task.delay(0.15, function()
		if token == markerToken then
			marker.Visible = false
		end
	end)
end)

-- Health ----------------------------------------------------------------
local healthBg = new("Frame", { Size = UDim2.fromOffset(280, 22), Position = UDim2.new(0, 24, 1, -48), BackgroundColor3 = Color3.fromRGB(15, 18, 28), BackgroundTransparency = 0.25, BorderSizePixel = 0 }, gui)
new("UICorner", { CornerRadius = UDim.new(0, 6) }, healthBg)
local healthFill = new("Frame", { Size = UDim2.fromScale(1, 1), BackgroundColor3 = Color3.fromRGB(90, 220, 140), BorderSizePixel = 0 }, healthBg)
new("UICorner", { CornerRadius = UDim.new(0, 6) }, healthFill)
local healthText = label({ Size = UDim2.fromScale(1, 1), TextSize = 14 }, healthBg)

-- Ammo ------------------------------------------------------------------
local weaponText = label({ Size = UDim2.fromOffset(240, 24), Position = UDim2.new(1, -264, 1, -92), TextSize = 18, TextXAlignment = Enum.TextXAlignment.Right, TextColor3 = Color3.fromRGB(200, 210, 230) }, gui)
local ammoText = label({ Size = UDim2.fromOffset(240, 48), Position = UDim2.new(1, -264, 1, -70), TextSize = 40, TextXAlignment = Enum.TextXAlignment.Right }, gui)

-- Wave banner -----------------------------------------------------------
local banner = label({ Size = UDim2.fromOffset(420, 36), Position = UDim2.new(0.5, -210, 0, 14), TextSize = 24, BackgroundTransparency = 0.35, BackgroundColor3 = Color3.fromRGB(15, 18, 28) }, gui)
new("UICorner", { CornerRadius = UDim.new(0, 8) }, banner)

-- Power bar -------------------------------------------------------------
local powerPanel = new("Frame", { Size = UDim2.fromOffset(420, 86), Position = UDim2.new(0.5, -210, 1, -104), BackgroundTransparency = 1 }, gui)
local ultBg = new("Frame", { Size = UDim2.new(1, 0, 0, 10), Position = UDim2.fromOffset(0, 72), BackgroundColor3 = Color3.fromRGB(15, 18, 28), BorderSizePixel = 0 }, powerPanel)
new("UICorner", { CornerRadius = UDim.new(0, 5) }, ultBg)
local ultFill = new("Frame", { Size = UDim2.fromScale(0, 1), BackgroundColor3 = Color3.fromRGB(120, 200, 255), BorderSizePixel = 0 }, ultBg)
new("UICorner", { CornerRadius = UDim.new(0, 5) }, ultFill)

local buttons = {}
for i, id in Config.PowerOrder do
	local def = Config.Powers[id]
	local btn = new("TextButton", {
		Size = UDim2.fromOffset(132, 62), Position = UDim2.fromOffset((i - 1) * 144, 0),
		BackgroundColor3 = Color3.fromRGB(25, 32, 52), BorderSizePixel = 0, AutoButtonColor = false,
		Text = "", 
	}, powerPanel)
	new("UICorner", { CornerRadius = UDim.new(0, 8) }, btn)
	new("UIStroke", { Color = Color3.fromRGB(120, 200, 255), Thickness = 1.5, Transparency = 0.4 }, btn)
	label({ Size = UDim2.new(1, 0, 0, 24), Position = UDim2.fromOffset(0, 6), Text = `[{def.Key}] {def.Name}`, TextSize = 15 }, btn)
	label({ Size = UDim2.new(1, 0, 0, 20), Position = UDim2.fromOffset(0, 32), Text = `{def.Cost} charge`, TextSize = 13, TextColor3 = Color3.fromRGB(150, 170, 200) }, btn)
	btn.Activated:Connect(function()
		Remotes.UsePower:FireServer(id)
	end)
	buttons[id] = btn
end

UserInputService.InputBegan:Connect(function(input, processed)
	if processed then
		return
	end
	for _, id in Config.PowerOrder do
		if input.KeyCode == Enum.KeyCode[Config.Powers[id].Key] then
			Remotes.UsePower:FireServer(id)
		end
	end
end)

-- Announcements -----------------------------------------------------------
local announceTitle = label({ Size = UDim2.new(1, 0, 0, 70), Position = UDim2.new(0, 0, 0.2, 0), TextSize = 56, TextTransparency = 1 }, gui)
local announceSub = label({ Size = UDim2.new(1, 0, 0, 30), Position = UDim2.new(0, 0, 0.2, 70), TextSize = 22, TextTransparency = 1, TextColor3 = Color3.fromRGB(200, 210, 230) }, gui)
Remotes.Announce.OnClientEvent:Connect(function(title, sub)
	announceTitle.Text, announceSub.Text = title, sub
	announceTitle.TextTransparency, announceSub.TextTransparency = 0, 0
	task.delay(2.2, function()
		TweenService:Create(announceTitle, TweenInfo.new(0.8), { TextTransparency = 1 }):Play()
		TweenService:Create(announceSub, TweenInfo.new(0.8), { TextTransparency = 1 }):Play()
	end)
end)

-- Refresh -----------------------------------------------------------------
local function refresh()
	local char = player.Character
	local hum = char and char:FindFirstChildOfClass("Humanoid")
	if hum then
		healthFill.Size = UDim2.fromScale(math.clamp(hum.Health / hum.MaxHealth, 0, 1), 1)
		healthText.Text = math.ceil(hum.Health) .. " HP"
	end

	local tool = char and char:FindFirstChildOfClass("Tool")
	local cfg = tool and Config.Weapons[tool:GetAttribute("WeaponName") or ""]
	if cfg then
		weaponText.Text = string.upper(tool.Name)
		ammoText.Text = tool:GetAttribute("Reloading") and "RELOADING" or `{tool:GetAttribute("Ammo") or 0} / {cfg.Mag}`
		ammoText.TextSize = tool:GetAttribute("Reloading") and 26 or 40
	else
		weaponText.Text, ammoText.Text = "", ""
	end

	local state = ReplicatedStorage:GetAttribute("State")
	local wave = ReplicatedStorage:GetAttribute("Wave") or 1
	if state == "Wave" then
		banner.Text = `WAVE {wave}  -  {ReplicatedStorage:GetAttribute("Remaining") or 0} left`
	else
		banner.Text = `WAVE {wave} in {ReplicatedStorage:GetAttribute("Countdown") or 0}s`
	end

	local ult = player:GetAttribute("Ult") or 0
	ultFill.Size = UDim2.fromScale(ult / Config.UltMax, 1)
	for id, btn in buttons do
		local ready = ult >= Config.Powers[id].Cost
		btn.BackgroundTransparency = ready and 0.05 or 0.55
	end
end

task.spawn(function()
	while true do
		refresh()
		task.wait(0.1)
	end
end)
