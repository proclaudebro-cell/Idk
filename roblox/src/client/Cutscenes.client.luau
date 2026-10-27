local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")
local RunService = game:GetService("RunService")
local TweenService = game:GetService("TweenService")
local Lighting = game:GetService("Lighting")
local Workspace = game:GetService("Workspace")

local player = Players.LocalPlayer
local camera = Workspace.CurrentCamera
local Remotes = ReplicatedStorage:WaitForChild("Remotes")

local gui = Instance.new("ScreenGui")
gui.Name = "Cutscene"
gui.ResetOnSpawn = false
gui.IgnoreGuiInset = true
gui.DisplayOrder = 10
gui.Parent = player:WaitForChild("PlayerGui")

local function bar(anchorY)
	local f = Instance.new("Frame")
	f.AnchorPoint = Vector2.new(0, anchorY)
	f.Position = UDim2.fromScale(0, anchorY)
	f.Size = UDim2.fromScale(1, 0)
	f.BackgroundColor3 = Color3.new(0, 0, 0)
	f.BorderSizePixel = 0
	f.Parent = gui
	return f
end
local topBar, bottomBar = bar(0), bar(1)

local function text(size, y)
	local t = Instance.new("TextLabel")
	t.BackgroundTransparency = 1
	t.Size = UDim2.new(1, 0, 0, size + 10)
	t.Position = UDim2.new(0, 0, y, 0)
	t.Font = Enum.Font.GothamBlack
	t.TextSize = size
	t.TextColor3 = Color3.new(1, 1, 1)
	t.TextStrokeTransparency = 0.6
	t.TextTransparency = 1
	t.TextStrokeTransparency = 1
	t.Parent = gui
	return t
end
local titleLabel, subLabel = text(64, 0.72), text(24, 0.72)
subLabel.Position = UDim2.new(0, 0, 0.72, 74)
subLabel.Font = Enum.Font.GothamBold

local flash = Instance.new("Frame")
flash.Size = UDim2.fromScale(1, 1)
flash.BackgroundColor3 = Color3.new(1, 1, 1)
flash.BackgroundTransparency = 1
flash.BorderSizePixel = 0
flash.Parent = gui

local grade = Instance.new("ColorCorrectionEffect")
grade.Name = "CutsceneGrade"
grade.Parent = Lighting

local controls
task.spawn(function()
	local ok, module = pcall(function()
		return require(player:WaitForChild("PlayerScripts"):WaitForChild("PlayerModule"))
	end)
	if ok then
		controls = module:GetControls()
	end
end)

local function tween(inst, time, props)
	TweenService:Create(inst, TweenInfo.new(time, Enum.EasingStyle.Sine), props):Play()
end

local function showTitle(title, sub)
	titleLabel.Text, subLabel.Text = title, sub or ""
	tween(titleLabel, 0.5, { TextTransparency = 0, TextStrokeTransparency = 0.6 })
	tween(subLabel, 0.5, { TextTransparency = sub and 0 or 1 })
end

local function hideTitle()
	tween(titleLabel, 0.4, { TextTransparency = 1, TextStrokeTransparency = 1 })
	tween(subLabel, 0.4, { TextTransparency = 1 })
end

local function doFlash(time)
	flash.BackgroundTransparency = 0
	tween(flash, time, { BackgroundTransparency = 1 })
end

local function smooth(a)
	return a * a * (3 - 2 * a)
end

-- Runs fn(alpha) every frame for `duration` seconds.
local function shot(duration, fn)
	local t0 = os.clock()
	while true do
		local a = math.min((os.clock() - t0) / duration, 1)
		fn(a, smooth(a))
		if a >= 1 then
			return
		end
		RunService.RenderStepped:Wait()
	end
end

local function casterPos(info)
	local char = info.Caster and info.Caster.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	return root and root.Position or info.Position, root and root.CFrame.LookVector or Vector3.zAxis
end

local function aim(pos, target, fov)
	camera.CFrame = CFrame.lookAt(pos, target)
	camera.FieldOfView = fov
end

local handlers = {}

function handlers.Nova(info)
	showTitle("RADIANT NOVA")
	shot(1.4, function(_, e)
		local p, look = casterPos(info)
		aim(p + look * (9 - 4 * e) + Vector3.new(0, 1 + 0.8 * e, 0), p + Vector3.new(0, 2, 0), 70 - 25 * e)
	end)
	doFlash(0.5)
	hideTitle()
	shot(1.8, function(_, e)
		local p, look = casterPos(info)
		aim(p - look * (8 + 6 * e) + Vector3.new(0, 0.5 + 2.5 * e, 0), p + Vector3.new(0, 4 + 10 * e, 0), 60 + 40 * e)
	end)
end

function handlers.Chrono(info)
	showTitle("CHRONO BREAK")
	tween(grade, 1, { Saturation = -0.8, Contrast = 0.2, TintColor = Color3.fromRGB(170, 200, 255) })
	local start = math.rad(200)
	shot(4, function(a, e)
		local p = casterPos(info)
		local ang = start + e * math.rad(220)
		local radius, height = 14 - 5 * e, 5 - 3 * e
		aim(p + Vector3.new(math.cos(ang) * radius, height, math.sin(ang) * radius), p + Vector3.new(0, 2, 0), 70 - 20 * e)
		if a > 0.85 and grade.Saturation < -0.5 then
			tween(grade, 0.6, { Saturation = 0, Contrast = 0, TintColor = Color3.new(1, 1, 1) })
		end
	end)
	hideTitle()
end

function handlers.Skyfall(info)
	showTitle("SKYFALL")
	shot(1.6, function(_, e)
		local p = casterPos(info)
		aim(p + Vector3.new(0, 3 + 90 * e, 10 + 25 * e), p + Vector3.new(0, 2, 0), 70 - 15 * e)
	end)
	hideTitle()
	shot(3.4, function(_, e)
		local p = casterPos(info)
		aim(p + Vector3.new(20 * e, 93 - 8 * e, 35 + 10 * e), p, 55 + 10 * e)
	end)
end

function handlers.BossIntro(info)
	local boss = info.Boss
	local root = boss and boss:WaitForChild("HumanoidRootPart", 3)
	if not root then
		return
	end
	showTitle(info.Title, info.Subtitle)
	shot(2.6, function(_, e)
		local p, look = root.Position, root.CFrame.LookVector
		aim(p + look * (16 - 7 * e) + Vector3.new(0, 2 - 1.5 * e, 0), p + Vector3.new(0, 6, 0), 60 - 15 * e)
	end)
	doFlash(0.3)
	shot(1.9, function(_, e)
		local p, look = root.Position, root.CFrame.LookVector
		local right = root.CFrame.RightVector
		aim(p + look * 9 + right * (10 * e) + Vector3.new(0, 4, 0), p + Vector3.new(0, 6, 0), 45 + 25 * e)
	end)
	hideTitle()
end

local playing = false
Remotes.Cutscene.OnClientEvent:Connect(function(id, info)
	local handler = handlers[id]
	if not handler or playing then
		return
	end
	playing = true
	task.spawn(function()
		player:SetAttribute("InCutscene", true)
		if controls then
			controls:Disable()
		end
		camera.CameraType = Enum.CameraType.Scriptable
		tween(topBar, 0.4, { Size = UDim2.fromScale(1, 0.11) })
		tween(bottomBar, 0.4, { Size = UDim2.fromScale(1, 0.11) })

		local ok, err = pcall(handler, info)
		if not ok then
			warn("Cutscene error:", err)
		end

		tween(topBar, 0.4, { Size = UDim2.fromScale(1, 0) })
		tween(bottomBar, 0.4, { Size = UDim2.fromScale(1, 0) })
		tween(grade, 0.4, { Saturation = 0, Contrast = 0, TintColor = Color3.new(1, 1, 1) })
		camera.FieldOfView = 70
		camera.CameraType = Enum.CameraType.Custom
		local hum = player.Character and player.Character:FindFirstChildOfClass("Humanoid")
		if hum then
			camera.CameraSubject = hum
		end
		if controls then
			controls:Enable()
		end
		player:SetAttribute("InCutscene", false)
		playing = false
	end)
end)
